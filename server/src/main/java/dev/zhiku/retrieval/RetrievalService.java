package dev.zhiku.retrieval;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.GrantLookup;
import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.document.DocumentService;
import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.Retrieval.Result;
import dev.zhiku.retrieval.Retrieval.Stage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权和版本效力在任何向量比较之前确定；只有已授权知识库中
 * 有效版本的 chunk 才能成为候选。精确检索；尚无改写、
 * 词法、融合或重排阶段，trace 会如实说明，而不是编造分数。
 */
@Service
public class RetrievalService {
  private final KnowledgeBaseAccess access;
  private final DocumentService documents;
  private final RetrievalRepository repository;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final RetrievalSettings settings;
  private final GrantLookup grants;
  private final TraceRepository traces;

  public RetrievalService(KnowledgeBaseAccess access, DocumentService documents, RetrievalRepository repository,
      ObjectProvider<EmbeddingProvider> embeddings, RetrievalSettings settings, GrantLookup grants, TraceRepository traces) {
    this.traces = traces;
    this.grants = grants;
    this.access = access;
    this.documents = documents;
    this.repository = repository;
    this.embeddings = embeddings;
    this.settings = settings;
  }

  public Result search(UUID kbId, String question, Integer k, Instant at, KnowledgeBaseAction action) {
    return search(kbId, question, k, at, action, 0);
  }

  /** 调试再运行：每次得到新的 traceId，按给定配置版本记录，不覆盖原轨迹。 */
  public Result search(UUID kbId, String question, Integer k, Instant at, KnowledgeBaseAction action, int configVersion) {
    var scope = access.require(kbId, action);
    return searchAuthorized(scope, kbId, question, k, at, "debug", UUID.randomUUID(), configVersion);
  }

  /** 问答、调试与评测共用的检索链路；每次都按 traceId 记录各阶段元数据，失败时也保留轨迹。 */
  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at, String origin, UUID traceId) {
    return searchAuthorized(scope, kbId, question, k, at, origin, traceId, 0);
  }

  /** pool 是送入重排的向量候选数，必须不小于最终保留的 k，且不超过 maxK。 */
  public record Rerank(boolean enabled, int pool) {
    public static Rerank off() {
      return new Rerank(false, 0);
    }
  }

  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at, String origin, UUID traceId, int configVersion) {
    return searchAuthorized(scope, kbId, question, k, at, origin, traceId, configVersion, Rerank.off());
  }

  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at, String origin, UUID traceId, int configVersion,
      Rerank rerank) {
    var use = rerank == null ? Rerank.off() : rerank;
    int keep = k == null ? settings.defaultK() : k;
    if (keep < 1 || keep > settings.maxK()) throw ApiException.unprocessable("k must be between 1 and " + settings.maxK());
    int fetch = keep;
    if (use.enabled()) {
      if (use.pool() < keep || use.pool() > settings.maxK()) {
        throw ApiException.unprocessable("rerankPool must be between k and " + settings.maxK());
      }
      fetch = use.pool();
    }
    var provider = embeddings.getIfAvailable();
    traces.insert(traceId, scope, kbId, origin, at, keep, provider == null ? null : provider.model(), configVersion);
    var rewrite = Stage.skipped("rewrite", "查询改写", "未改写，保留原问题");
    var later = List.of(Stage.skipped("lexical", "词法召回", "未启用"), Stage.skipped("fusion", "融合排序", "未启用"),
        Stage.skipped("rerank", "重排", "未开启"));
    var vectorStarted = Instant.now();
    if (provider == null) {
      traces.addStages(traceId, stages(rewrite, Stage.timed("vector", "向量召回", "failed", vectorStarted, "未配置 embedding 模型"), later));
      throw ApiException.unavailable("Embedding model is not configured");
    }
    float[] query;
    try {
      var vectors = provider.embed(List.of(question));
      EmbeddingVectors.validate(vectors, 1);
      query = vectors.getFirst();
    } catch (RuntimeException e) {
      traces.addStages(traceId, stages(rewrite, Stage.timed("vector", "向量召回", "failed", vectorStarted, "向量化失败"), later));
      throw ApiException.unavailable("Embedding service is unavailable");
    }
    // 效力和授权在（较慢的）embedding 调用之后读取，且查询会重新检查删除和撤回，
    // 因此请求进行中提交的变更不会被披露。之后的变更由调用方检查。
    var eligible = documents.eligibleVersionIds(scope, kbId, at);
    var candidates = grants.grantedKnowledgeBases(scope.enterpriseId(), scope.userId()).contains(kbId)
        ? repository.nearest(eligible, provider.model(), query, fetch) : List.<Retrieval.Candidate>of();
    var vector = Stage.timed("vector", "向量召回", "success", vectorStarted,
        "精确检索 · " + eligible.size() + " 个合格版本 · " + candidates.size() + " 条候选");
    if (use.enabled()) {
      var rerankStarted = Instant.now();
      candidates = LexicalReranker.rerank(question, candidates, keep);
      later = List.of(Stage.skipped("lexical", "词法召回", "未启用"), Stage.skipped("fusion", "融合排序", "未启用"),
          Stage.timed("rerank", "重排", "success", rerankStarted, "字符二元组 BM25 与向量分加权 · 保留 " + candidates.size() + " 条"));
    }
    var stages = stages(rewrite, vector, later);
    traces.setDimension(traceId, query.length);
    traces.addStages(traceId, stages);
    traces.addCandidates(traceId, candidates);
    return new Result(traceId, question, at, provider.model(), query.length, stages, candidates, configVersion);
  }

  private static List<Stage> stages(Stage rewrite, Stage vector, List<Stage> later) {
    var all = new java.util.ArrayList<Stage>();
    all.add(rewrite);
    all.add(vector);
    all.addAll(later);
    return all;
  }

  /** 问答在检索之后追加"上下文与生成"阶段；轨迹不存在（检索前即结束）时不记录。 */
  public void recordGeneration(UUID traceId, Stage stage) {
    if (traceId != null && traces.exists(traceId)) traces.addStages(traceId, List.of(stage));
  }

  public void linkMessage(UUID traceId, UUID messageId) {
    traces.linkMessage(traceId, messageId);
  }

  public Retrieval.TraceView trace(UUID kbId, UUID traceId) {
    access.require(kbId, KnowledgeBaseAction.DEBUG_AND_EVALUATE);
    return traces.find(kbId, traceId).orElseThrow(ApiException::notFound);
  }

  /** 重建索引会整体替换切块：引用的切块已不存在时，答案不能再按这些引用披露。 */
  public boolean allChunksExist(List<UUID> chunkIds) {
    return chunkIds.isEmpty() || repository.allChunksExist(chunkIds);
  }

  /** 保留期清理：删除早于 before 的轨迹，最多 limit 条；返回删除数。 */
  @Transactional
  public int purgeTracesBefore(Instant before, int limit) {
    return traces.deleteCreatedBefore(before, limit);
  }
}
