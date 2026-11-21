package dev.zhiku.retrieval;

import dev.zhiku.auth.AccessScope;
import dev.zhiku.auth.GrantLookup;
import dev.zhiku.auth.KnowledgeBaseAction;
import dev.zhiku.document.DocumentService;
import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.Retrieval.Candidate;
import dev.zhiku.retrieval.Retrieval.Result;
import dev.zhiku.retrieval.Retrieval.Stage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权和版本效力在任何向量比较之前确定；只有已授权知识库中
 * 有效版本的 chunk 才能成为候选。各阶段（改写、向量、词法、融合、门槛、重排）
 * 是否运行都如实写进 trace，未运行的阶段不编造分数。
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
    return searchAuthorized(scope, kbId, question, k, at, "debug", UUID.randomUUID(), configVersion, defaultRerank(k == null ? settings.defaultK() : k));
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
    return searchAuthorized(scope, kbId, question, k, at, origin, traceId, configVersion, rerank, null);
  }

  /** 部署配置的重排设置：问答与检索调试默认使用，评测可显式覆盖。 */
  public Rerank defaultRerank(int k) {
    if (!settings.rerank()) return Rerank.off();
    return new Rerank(true, Math.min(settings.maxK(), Math.max(k, settings.rerankPool())));
  }

  public RetrievalSettings settings() {
    return settings;
  }

  /**
   * 完整链路：查询改写（由问答传入的阶段说明）→ 向量召回（精确或 HNSW）→ 词法召回（二元组）→ RRF 融合 →
   * 相关性门槛 → 重排。授权和效力在任何比较之前确定；门槛之后为空表示"资料不足"，由调用方拒答。
   */
  public Result searchAuthorized(AccessScope scope, UUID kbId, String question, Integer k, Instant at, String origin, UUID traceId, int configVersion,
      Rerank rerank, Stage rewriteStage) {
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
    var rewrite = rewriteStage != null ? rewriteStage : Stage.skipped("rewrite", "查询改写", "未改写，保留原问题");
    var notRun = List.of(Stage.skipped("lexical", "词法召回", "未执行"), Stage.skipped("fusion", "融合排序", "未执行"),
        Stage.skipped("relevance", "相关性门槛", "未执行"), Stage.skipped("rerank", "重排", "未执行"));
    var vectorStarted = Instant.now();
    if (provider == null) {
      traces.addStages(traceId, stages(rewrite, Stage.timed("vector", "向量召回", "failed", vectorStarted, "未配置 embedding 模型"), notRun));
      throw ApiException.unavailable("Embedding model is not configured");
    }
    float[] query;
    try {
      var vectors = provider.embed(List.of(question));
      EmbeddingVectors.validate(vectors, 1);
      query = vectors.getFirst();
    } catch (RuntimeException e) {
      traces.addStages(traceId, stages(rewrite, Stage.timed("vector", "向量召回", "failed", vectorStarted, "向量化失败"), notRun));
      throw ApiException.unavailable("Embedding service is unavailable");
    }
    // 效力和授权在（较慢的）embedding 调用之后读取，且查询会重新检查删除和撤回，
    // 因此请求进行中提交的变更不会被披露。之后的变更由调用方检查。
    var eligible = documents.eligibleVersionIds(scope, kbId, at);
    boolean granted = grants.grantedKnowledgeBases(scope.enterpriseId(), scope.userId()).contains(kbId);
    boolean ann = granted && repository.chunkCount(eligible) >= settings.annMinChunks();
    var vectorHits = !granted ? List.<Candidate>of()
        : ann ? repository.approximate(eligible, provider.model(), query, fetch) : repository.nearest(eligible, provider.model(), query, fetch);
    var vector = Stage.timed("vector", "向量召回", "success", vectorStarted,
        (ann ? "HNSW 近似检索" : "精确检索") + " · " + eligible.size() + " 个合格版本 · " + vectorHits.size() + " 条候选");

    var terms = Bigrams.distinct(question);
    var later = new ArrayList<Stage>();
    List<Candidate> pool;
    if (settings.hybrid()) {
      var lexicalStarted = Instant.now();
      var lexicalHits = granted ? repository.lexical(eligible, provider.model(), query, terms, fetch) : List.<Candidate>of();
      later.add(Stage.timed("lexical", "词法召回", "success", lexicalStarted, "字符二元组 · " + terms.size() + " 个查询词 · " + lexicalHits.size() + " 条候选"));
      var fusionStarted = Instant.now();
      pool = fuse(vectorHits, lexicalHits, fetch);
      later.add(Stage.timed("fusion", "融合排序", "success", fusionStarted, "RRF（k=" + RRF_K + "）· 合并为 " + pool.size() + " 条"));
    } else {
      pool = vectorHits;
      later.add(Stage.skipped("lexical", "词法召回", "未启用"));
      later.add(Stage.skipped("fusion", "融合排序", "未启用"));
    }

    var gateStarted = Instant.now();
    var scored = pool.stream().map(c -> c.withLexical(Bigrams.coverage(terms, Bigrams.distinct(c.searchText())))).toList();
    var relevant = scored.stream().filter(this::relevant).toList();
    later.add(Stage.timed("relevance", "相关性门槛", "success", gateStarted, "保留 " + relevant.size() + " / " + scored.size() + " 条（向量 ≥ "
        + settings.minScore() + " 或词覆盖 ≥ " + Math.round(settings.minLexicalCoverage() * 100) + "%）"));

    List<Candidate> candidates;
    if (use.enabled()) {
      var rerankStarted = Instant.now();
      candidates = LexicalReranker.rerank(question, relevant, keep);
      later.add(Stage.timed("rerank", "重排", "success", rerankStarted, "字符二元组 BM25 与向量分加权 · 保留 " + candidates.size() + " 条"));
    } else {
      candidates = new ArrayList<>();
      for (int i = 0; i < Math.min(keep, relevant.size()); i++) candidates.add(relevant.get(i).withScore(relevant.get(i).score(), i + 1));
      later.add(Stage.skipped("rerank", "重排", "未开启"));
    }
    var stages = stages(rewrite, vector, later);
    traces.setDimension(traceId, query.length);
    traces.addStages(traceId, stages);
    traces.addCandidates(traceId, candidates);
    return new Result(traceId, question, at, provider.model(), query.length, stages, List.copyOf(candidates), configVersion);
  }

  static final int RRF_K = 60;

  /** 倒数排名融合：两路各自的名次贡献 1/(k+rank)，按和排序；只在一路出现的候选也保留。 */
  static List<Candidate> fuse(List<Candidate> vector, List<Candidate> lexical, int limit) {
    var byId = new java.util.LinkedHashMap<UUID, Candidate>();
    var fused = new java.util.HashMap<UUID, Double>();
    for (int i = 0; i < vector.size(); i++) {
      byId.putIfAbsent(vector.get(i).chunkId(), vector.get(i));
      fused.merge(vector.get(i).chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
    }
    for (int i = 0; i < lexical.size(); i++) {
      byId.putIfAbsent(lexical.get(i).chunkId(), lexical.get(i));
      fused.merge(lexical.get(i).chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
    }
    var order = new ArrayList<>(byId.values());
    order.sort(java.util.Comparator.<Candidate>comparingDouble(c -> fused.get(c.chunkId())).reversed()
        .thenComparing(java.util.Comparator.comparingDouble(Candidate::vectorScore).reversed())
        .thenComparing(c -> c.chunkId().toString()));
    var out = new ArrayList<Candidate>();
    for (int i = 0; i < Math.min(limit, order.size()); i++) out.add(order.get(i).withFused(fused.get(order.get(i).chunkId()), i + 1));
    return out;
  }

  private boolean relevant(Candidate c) {
    return c.vectorScore() >= settings.minScore() || (c.lexicalScore() != null && c.lexicalScore() >= settings.minLexicalCoverage());
  }

  private static List<Stage> stages(Stage rewrite, Stage vector, List<Stage> later) {
    var all = new ArrayList<Stage>();
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
