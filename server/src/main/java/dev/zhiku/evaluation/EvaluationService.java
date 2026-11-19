package dev.zhiku.evaluation;

import static dev.zhiku.auth.KnowledgeBaseAction.DEBUG_AND_EVALUATE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.audit.AuditLog;
import dev.zhiku.chat.ChatModel;
import dev.zhiku.chat.ChatSettings;
import dev.zhiku.document.DocumentRepository;
import dev.zhiku.document.DocumentService;
import dev.zhiku.ingestion.IngestionSettings;
import dev.zhiku.ingestion.TextChunker;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import dev.zhiku.retrieval.Retrieval.Candidate;
import dev.zhiku.retrieval.RetrievalService;
import dev.zhiku.retrieval.RetrievalSettings;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 真值是内容版本中的来源片段，与 chunk id 无关。每次运行把片段映射到当前切块：
 * 映射不上是准备失败，不是检索未命中。检索指标始终计算。
 * 生成和评审是可选项：没有对话模型时不编造答案或分数。重排未接入，切块策略不能在运行时切换。
 */
@Service
public class EvaluationService {
  static final Set<String> LABELS = Set.of("answerable", "clarify", "handoff");

  /** occurrence（从 1 开始）从多个相同段落中选出一个；重复引文需 occurrence 或经过原文核验的 startOffset。 */
  record EvidenceInput(@NotNull UUID versionId, @NotBlank @Size(max = 2000) String quote, @Positive Integer occurrence, @Min(0) Integer startOffset) {}

  /** 来源版本被撤回或其文档被删除后，quote 为 null 且 available 为 false；片段保留。 */
  record EvidenceView(UUID versionId, String documentTitle, String versionLabel, int startOffset, int endOffset, String quote, boolean available) {}

  /** 任一证据来源撤回或删除后 sourceAvailable 为 false，参考答案与必要事实随之隐藏（它们可能摘抄原文）。 */
  record CaseView(UUID id, String question, String label, String status, String source, Instant createdAt, List<EvidenceView> evidence,
      UUID originMessageId, String expectedAnswer, List<String> requiredFacts, String appliesTo, Instant queryAt, boolean sourceAvailable) {}

  /** PRD E01 的样本字段；queryAt 为空表示按评测运行时刻判断效力。 */
  record Details(String expectedAnswer, List<String> requiredFacts, String appliesTo, Instant queryAt) {

    List<String> facts() {
      var facts = requiredFacts == null ? List.<String>of() : requiredFacts;
      if (facts.size() > 10) throw ApiException.unprocessable("At most 10 required facts");
      if (facts.stream().anyMatch(f -> f == null || f.isBlank() || f.strip().length() > 200)) {
        throw ApiException.unprocessable("Required facts must be 1 to 200 characters");
      }
      return facts.stream().map(String::strip).toList();
    }

    String answer() {
      return expectedAnswer == null || expectedAnswer.isBlank() ? null : expectedAnswer.strip();
    }

    String audience() {
      return appliesTo == null || appliesTo.isBlank() ? null : appliesTo.strip();
    }
  }

  public record Ratio(int num, int den) {}

  /**
   * mrr / p50Ms / faithfulness / relevance / 端到端分位在旧的纯检索摘要里不存在，读出来是 null。
   * judged 是给出了忠实度的用例数。indexChangedDuringRun：运行期间有索引任务完成，应当重跑。
   */
  public record Summary(int cases, int evaluated, int prepFailed, int notApplicable, Ratio hit, Ratio recall, Double recallMacro, Long p95Ms,
      int sourceUnavailable, boolean indexChangedDuringRun, Double mrr, Long p50Ms, Double faithfulness, Double relevance, Long p50E2eMs, Long p95E2eMs,
      int judged) {}

  /** 本次 top-K 中的一条切块。overlapsEvidence 只表示与期望片段相交，不表示完整覆盖。 */
  public record RetrievedHit(int rank, UUID chunkId, UUID versionId, String documentTitle, String versionLabel, int startOffset, int endOffset,
      double score, String text, boolean overlapsEvidence) {}

  /** sourceUnavailable：某个证据版本当前无效；此类用例会被报告，但不计入分数。 */
  public record ResultView(UUID caseId, String question, String label, boolean mappingFailed, boolean hit, int evidenceHit, int evidenceTotal, int latencyMs,
      boolean sourceUnavailable, Integer firstRelevantRank, String answer, Integer generationMs, Integer promptTokens, Integer completionTokens,
      Double faithfulness, Double relevance, String failure, String citation, String judgeReason, List<RetrievedHit> retrieved) {}

  /** chunkMaxChars / chunkOverlap 是部署切块参数，运行本身不重建索引。rerankPool 是送入重排的向量条数。 */
  public record RunView(UUID id, int k, Integer contextChunks, Integer chunkMaxChars, Integer chunkOverlap, boolean rerankEnabled, Integer rerankPool,
      boolean generate, String judge, String model, String chatModel, Instant createdAt, Summary summary, List<ResultView> results) {}

  /** judge 只接受 llm 或 simulated。chunkStrategy 只能是 fixed-overlap 或空。rerankPool 不小于 k。 */
  public record RunOptions(Integer k, Integer contextChunks, Boolean generate, String judge, Boolean rerank, Integer rerankPool, String chunkStrategy) {}

  public record EvalSettings(String chunkStrategy, int chunkMaxChars, int chunkOverlap, boolean rerankEnabled, String rerank, int maxK,
      int defaultContextChunks, int maxContextChunks, boolean chatConfigured, String chatModel) {}

  public record ImportResult(int created, int approved, boolean example, String name) {}

  private record Span(UUID versionId, int start, int end) {}

  private static final TypeReference<List<RetrievedHit>> HITS = new TypeReference<>() {};

  private final KnowledgeBaseAccess access;
  private final DocumentRepository documents;
  private final DocumentService eligibility;
  private final TransactionTemplate tx;
  private final RetrievalService retrieval;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AuditLog audit;
  private final IngestionSettings ingestion;
  private final ChatSettings chatSettings;
  private final RetrievalSettings retrievalSettings;
  private final ObjectProvider<ChatModel> models;

  EvaluationService(KnowledgeBaseAccess access, DocumentRepository documents, DocumentService eligibility, RetrievalService retrieval,
      JdbcTemplate jdbc, ObjectMapper json, AuditLog audit, TransactionTemplate tx, IngestionSettings ingestion, ChatSettings chatSettings,
      RetrievalSettings retrievalSettings, ObjectProvider<ChatModel> models) {
    this.tx = tx;
    this.eligibility = eligibility;
    this.access = access;
    this.documents = documents;
    this.retrieval = retrieval;
    this.jdbc = jdbc;
    this.json = json;
    this.audit = audit;
    this.ingestion = ingestion;
    this.chatSettings = chatSettings;
    this.retrievalSettings = retrievalSettings;
    this.models = models;
  }

  @Transactional
  UUID createCase(UUID kbId, String question, String label, List<EvidenceInput> evidence, String source, UUID originMessageId, Details details) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    if (!LABELS.contains(label)) throw ApiException.unprocessable("Unknown label");
    var facts = details.facts();
    requireEntries(evidence);
    if (originMessageId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE m.id = ? AND c.kb_id = ?)",
        Boolean.class, originMessageId, kbId))) {
      throw ApiException.unprocessable("Origin message is not in this knowledge base");
    }
    var inputs = evidence == null ? List.<EvidenceInput>of() : evidence;
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO eval_case (id, kb_id, question, label, source, created_by, origin_message_id, origin_feedback_version,
                               expected_answer, required_facts, applies_to, query_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, (SELECT version FROM feedback WHERE message_id = ?), ?, ?, ?, ?)
        """, id, kbId, question, label, source == null ? "manual" : source, scope.userId(), originMessageId, originMessageId,
        details.answer(), facts.toArray(String[]::new), details.audience(), details.queryAt() == null ? null : java.sql.Timestamp.from(details.queryAt()));
    insertEvidence(kbId, id, inputs);
    audit.record(scope, "EVAL_CASE_CREATE", "eval-case:" + id, "OK", Map.of("label", label, "evidence", inputs.size()));
    return id;
  }

  /** 在其版本的规范化文本中定位每条引文，并存储字符片段。 */
  /** 元素约束按条目校验（@Valid）；null 条目属于无效输入，在任何写入前检查。 */
  static void requireEntries(List<EvidenceInput> inputs) {
    if (inputs != null && inputs.stream().anyMatch(java.util.Objects::isNull)) throw ApiException.unprocessable("Evidence entries must not be empty");
  }

  private void insertEvidence(UUID kbId, UUID caseId, List<EvidenceInput> inputs) {
    var seen = new HashSet<Span>();
    for (int i = 0; i < inputs.size(); i++) {
      var e = inputs.get(i);
      var version = documents.findVersion(e.versionId()).filter(v -> v.kbId().equals(kbId))
          .orElseThrow(() -> ApiException.unprocessable("Evidence version is not in this knowledge base"));
      var text = normalizedContent(version.id());
      var quote = TextChunker.normalize(e.quote() == null ? "" : e.quote()).strip();
      int at;
      if (e.startOffset() != null) {
        at = e.startOffset();
        if (e.occurrence() != null || at < 0 || at > text.length() - quote.length() || quote.length() < 2
            || !text.startsWith(quote, at)) throw ApiException.unprocessable("Evidence position does not match the source quote");
      } else at = locate(text, quote, e.occurrence());
      if (!seen.add(new Span(version.id(), at, at + quote.length()))) throw ApiException.unprocessable("The same evidence span is listed twice");
      jdbc.update("INSERT INTO eval_evidence (case_id, ordinal, version_id, start_offset, end_offset, quote) VALUES (?, ?, ?, ?, ?, ?)",
          caseId, i + 1, version.id(), at, at + quote.length(), quote);
    }
  }

  /** 所选出现位置的起点；其他位置的相同段落是不同的真值，因此拒绝有歧义的情况。 */
  static int locate(String text, String quote, Integer occurrence) {
    if (quote.length() < 2) throw ApiException.unprocessable("Evidence quote was not found in the selected version");
    var starts = new ArrayList<Integer>();
    for (int at = text.indexOf(quote); at >= 0; at = text.indexOf(quote, at + 1)) starts.add(at);
    if (starts.isEmpty()) throw ApiException.unprocessable("Evidence quote was not found in the selected version");
    if (occurrence == null) {
      if (starts.size() > 1) throw ApiException.unprocessable("Evidence quote occurs " + starts.size() + " times; choose which occurrence");
      return starts.getFirst();
    }
    if (occurrence > starts.size()) throw ApiException.unprocessable("Evidence quote occurs only " + starts.size() + " times");
    return starts.get(occurrence - 1);
  }


  /** 替换候选用例的证据；已批准用例被冻结，以保持历史运行可比较。 */
  @Transactional
  void setEvidence(UUID kbId, UUID caseId, List<EvidenceInput> evidence) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    requireEntries(evidence);
    // 锁定用例行：进行中的批准先完成，然后用例被冻结（C22）。
    var status = jdbc.queryForList("SELECT status FROM eval_case WHERE id = ? AND kb_id = ? FOR UPDATE", String.class, caseId, kbId);
    if (status.isEmpty()) throw ApiException.notFound();
    if (!"candidate".equals(status.getFirst())) throw ApiException.conflict("Approved cases cannot change evidence");
    jdbc.update("DELETE FROM eval_evidence WHERE case_id = ?", caseId);
    insertEvidence(kbId, caseId, evidence == null ? List.of() : evidence);
    audit.record(scope, "EVAL_CASE_EVIDENCE", "eval-case:" + caseId, "OK", Map.of("evidence", evidence == null ? 0 : evidence.size()));
  }

  private String normalizedContent(UUID versionId) {
    var bytes = jdbc.query("SELECT content FROM content_blob WHERE version_id = ?", (rs, i) -> rs.getBytes(1), versionId);
    if (bytes.isEmpty()) throw ApiException.unprocessable("Evidence version has no content");
    return TextChunker.normalize(new String(bytes.getFirst(), StandardCharsets.UTF_8).replaceFirst("^﻿", ""));
  }

  /** 只有候选用例可以补齐或修改样本字段；已批准的用例冻结。 */
  @Transactional
  void setDetails(UUID kbId, UUID caseId, Details details) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    var facts = details.facts();
    var status = jdbc.queryForList("SELECT status FROM eval_case WHERE id = ? AND kb_id = ? FOR UPDATE", String.class, caseId, kbId);
    if (status.isEmpty()) throw ApiException.notFound();
    if (!"candidate".equals(status.getFirst())) throw ApiException.conflict("Approved cases cannot change");
    jdbc.update("UPDATE eval_case SET expected_answer = ?, required_facts = ?, applies_to = ?, query_at = ? WHERE id = ?",
        details.answer(), facts.toArray(String[]::new), details.audience(), details.queryAt() == null ? null : java.sql.Timestamp.from(details.queryAt()), caseId);
    audit.record(scope, "EVAL_CASE_DETAILS", "eval-case:" + caseId, "OK", Map.of("facts", facts.size()));
  }

  @Transactional
  void approve(UUID kbId, UUID caseId) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    // 与证据编辑使用相同的行锁，因此此处检查的证据就是被冻结的证据。
    jdbc.queryForList("SELECT 1 FROM eval_case WHERE id = ? AND kb_id = ? FOR UPDATE", Integer.class, caseId, kbId);
    var incomplete = jdbc.queryForList("""
        SELECT 1 FROM eval_case c WHERE c.id = ? AND c.kb_id = ? AND c.label = 'answerable'
          AND (NOT EXISTS (SELECT 1 FROM eval_evidence e WHERE e.case_id = c.id) OR c.expected_answer IS NULL OR cardinality(c.required_facts) = 0)
        """, Integer.class, caseId, kbId);
    if (!incomplete.isEmpty()) throw ApiException.unprocessable("Answerable cases need evidence, a reference answer and required facts before approval");
    // 与文档删除/版本撤回互斥；只核验可访问来源，不把历史或未来效力当作错误。
    var available = jdbc.query("""
        SELECT d.kb_id, d.deleted_at, v.withdrawn_at
        FROM eval_evidence e JOIN content_version v ON v.id = e.version_id JOIN document d ON d.id = v.document_id
        WHERE e.case_id = ? ORDER BY d.id, v.id FOR SHARE OF d, v
        """, (rs, i) -> kbId.equals(rs.getObject("kb_id", UUID.class))
            && rs.getTimestamp("deleted_at") == null && rs.getTimestamp("withdrawn_at") == null, caseId);
    int evidenceCount = jdbc.queryForObject("SELECT count(*) FROM eval_evidence WHERE case_id = ?", Integer.class, caseId);
    if (available.size() != evidenceCount || available.stream().anyMatch(ok -> !ok)) {
      throw ApiException.unprocessable("Evidence source is unavailable; update the candidate before approval");
    }
    if (jdbc.update("UPDATE eval_case SET status = 'approved', approved_by = ? WHERE id = ? AND kb_id = ? AND status = 'candidate'",
        scope.userId(), caseId, kbId) != 1) {
      throw ApiException.conflict("Case is not a candidate in this knowledge base");
    }
    audit.record(scope, "EVAL_CASE_APPROVE", "eval-case:" + caseId, "OK", Map.of());
  }

  @Transactional(readOnly = true)
  List<CaseView> cases(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var evidence = new LinkedHashMap<UUID, List<EvidenceView>>();
    jdbc.query("""
        SELECT e.case_id, e.version_id, d.title, v.version_label, e.start_offset, e.end_offset, e.quote,
               d.deleted_at IS NULL AND v.withdrawn_at IS NULL AS available
        FROM eval_evidence e JOIN eval_case c ON c.id = e.case_id JOIN content_version v ON v.id = e.version_id JOIN document d ON d.id = v.document_id
        WHERE c.kb_id = ? ORDER BY e.case_id, e.ordinal
        """, rs -> {
          evidence.computeIfAbsent(rs.getObject("case_id", UUID.class), k -> new ArrayList<>()).add(new EvidenceView(
              rs.getObject("version_id", UUID.class), rs.getString("title"), rs.getString("version_label"),
              rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getBoolean("available") ? rs.getString("quote") : null,
              rs.getBoolean("available")));
        }, kbId);
    return jdbc.query("""
        SELECT id, question, label, status, source, created_at, origin_message_id, expected_answer, required_facts, applies_to, query_at
        FROM eval_case WHERE kb_id = ? ORDER BY created_at DESC, id
        """,
        (rs, i) -> {
          var id = rs.getObject("id", UUID.class);
          var spans = evidence.getOrDefault(id, List.of());
          boolean available = spans.stream().allMatch(EvidenceView::available);
          return new CaseView(id, rs.getString("question"), rs.getString("label"), rs.getString("status"), rs.getString("source"),
              rs.getTimestamp("created_at").toInstant(), spans, rs.getObject("origin_message_id", UUID.class),
              available ? rs.getString("expected_answer") : null,
              available ? List.of((String[]) rs.getArray("required_facts").getArray()) : List.of(), rs.getString("applies_to"),
              rs.getTimestamp("query_at") == null ? null : rs.getTimestamp("query_at").toInstant(), available);
        }, kbId);
  }

  /** 配置验证沿用的入口：只做检索评测，不生成、不评审。 */
  public RunView run(UUID kbId, Integer requestedK) {
    return run(kbId, new RunOptions(requestedK, null, false, null, null, null, null));
  }

  /** 逐条检索可能等待外部服务，不放在事务里；结果在最后一个短事务中写入。 */
  public RunView run(UUID kbId, RunOptions options) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    if (options != null && options.chunkStrategy() != null && !options.chunkStrategy().isBlank() && !"fixed-overlap".equals(options.chunkStrategy().strip())) {
      throw ApiException.unprocessable("切块只有部署配置的定长重叠（fixed-overlap），评测运行不能切换切块策略或触发重建");
    }
    int maxK = retrievalSettings.maxK();
    int k = options == null || options.k() == null ? 5 : options.k();
    if (k < 1 || k > maxK) throw ApiException.unprocessable("k must be between 1 and " + maxK);
    boolean rerank = options != null && Boolean.TRUE.equals(options.rerank());
    Integer rerankPool = null;
    if (rerank) {
      int pool = options.rerankPool() == null ? Math.min(maxK, Math.max(k, k * 4)) : options.rerankPool();
      if (pool < k || pool > maxK) throw ApiException.unprocessable("rerankPool must be between k and " + maxK);
      rerankPool = pool;
    }
    boolean generate = options != null && Boolean.TRUE.equals(options.generate());
    String judge = options == null || options.judge() == null || options.judge().isBlank() ? null : options.judge().strip();
    if (judge != null && !judge.equals("llm") && !judge.equals("simulated")) throw ApiException.unprocessable("judge must be llm or simulated");
    var chat = models.getIfAvailable();
    if (generate && "llm".equals(judge) && chat == null) throw ApiException.unprocessable("Chat model is not configured");
    String judgeUsed = !generate ? null : judge != null ? judge : chat == null ? null : "llm";
    int maxContext = maxContextChunks();
    int contextChunks;
    if (options == null || options.contextChunks() == null) contextChunks = Math.min(Math.max(chatSettings.contextChunks(), 1), k);
    else {
      contextChunks = options.contextChunks();
      if (contextChunks < 1 || contextChunks > maxContext || contextChunks > k) {
        throw ApiException.unprocessable("contextChunks must be between 1 and min(" + maxContext + ", k)");
      }
    }
    var runAt = Instant.now();
    var indexMarkBefore = indexMark(kbId);
    record Pending(UUID id, String question, String label, String queryAt, String expected, String[] facts) {}
    var cases = jdbc.query("""
        SELECT id, question, label, query_at, expected_answer, required_facts
        FROM eval_case WHERE kb_id = ? AND status = 'approved' ORDER BY created_at, id
        """, (rs, i) -> {
          var facts = rs.getArray("required_facts");
          return new Pending(rs.getObject("id", UUID.class), rs.getString("question"), rs.getString("label"),
              rs.getTimestamp("query_at") == null ? null : rs.getTimestamp("query_at").toInstant().toString(), rs.getString("expected_answer"),
              facts == null ? new String[0] : (String[]) facts.getArray());
        }, kbId);
    var eligibleAt = new java.util.HashMap<Instant, Set<UUID>>();
    var results = new ArrayList<ResultView>();
    String model = null;
    int notApplicable = 0;

    for (var c : cases) {
      if (!"answerable".equals(c.label())) {
        notApplicable++;
        continue;
      }
      var spans = jdbc.query("SELECT version_id, start_offset, end_offset FROM eval_evidence WHERE case_id = ? ORDER BY ordinal",
          (rs, i) -> new Span(rs.getObject("version_id", UUID.class), rs.getInt("start_offset"), rs.getInt("end_offset")), c.id());
      // 用例有查询时点时按该时点判断效力并检索，例如对将来生效的新规提问。
      var at = c.queryAt() == null ? runAt : Instant.parse(c.queryAt());
      var eligible = eligibleAt.computeIfAbsent(at, t -> new HashSet<>(eligibility.eligibleVersionIds(scope, kbId, t)));
      if (!spans.stream().allMatch(sp -> eligible.contains(sp.versionId()))) {
        results.add(retrievedRow(c.id(), c.question(), c.label(), false, false, 0, spans.size(), 0, true, null, "来源已失效", List.of()));
        continue;
      }
      var mapped = new ArrayList<Map<UUID, int[]>>();
      for (var sp : spans) {
        var overlapping = new LinkedHashMap<UUID, int[]>();
        jdbc.query("SELECT id, start_offset, end_offset FROM chunk WHERE version_id = ? AND start_offset < ? AND end_offset > ?",
            rs -> {
              overlapping.put(rs.getObject("id", UUID.class), new int[] {rs.getInt("start_offset"), rs.getInt("end_offset")});
            }, sp.versionId(), sp.end(), sp.start());
        mapped.add(overlapping);
      }
      if (mapped.stream().anyMatch(Map::isEmpty)) {
        results.add(retrievedRow(c.id(), c.question(), c.label(), true, false, 0, spans.size(), 0, false, null, "证据无法映射到当前切块", List.of()));
        continue;
      }
      long started = System.nanoTime();
      var trace = retrieval.searchAuthorized(scope, kbId, c.question(), k, at, "evaluation", UUID.randomUUID(), 0,
          new dev.zhiku.retrieval.RetrievalService.Rerank(rerank, rerankPool == null ? 0 : rerankPool));
      int latency = (int) ((System.nanoTime() - started) / 1_000_000);
      model = trace.embeddingModel();
      var retrieved = new HashSet<UUID>();
      trace.candidates().stream().map(Candidate::chunkId).forEach(retrieved::add);
      // 只有检索到的 chunk 合起来覆盖整个片段才算检索到，而不是只要有一个字符重叠。
      int covered = 0;
      for (int i = 0; i < spans.size(); i++) {
        var pieces = mapped.get(i).entrySet().stream().filter(en -> retrieved.contains(en.getKey())).map(Map.Entry::getValue).toList();
        if (covers(spans.get(i).start(), spans.get(i).end(), pieces)) covered++;
      }
      var hits = new ArrayList<RetrievedHit>();
      var chunkRefs = new ArrayList<EvalMetrics.ChunkRef>();
      for (var candidate : trace.candidates()) {
        boolean overlaps = spans.stream().anyMatch(span -> span.versionId().equals(candidate.versionId())
            && EvalMetrics.overlaps(candidate.startOffset(), candidate.endOffset(), span.start(), span.end()));
        hits.add(new RetrievedHit(candidate.rank(), candidate.chunkId(), candidate.versionId(), candidate.title(), candidate.versionLabel(),
            candidate.startOffset(), candidate.endOffset(), candidate.score(), candidate.text(), overlaps));
        chunkRefs.add(new EvalMetrics.ChunkRef(candidate.versionId(), candidate.startOffset(), candidate.endOffset(), candidate.rank()));
      }
      var rank = EvalMetrics.firstRelevantRank(chunkRefs, spans.stream().map(span -> new EvalMetrics.SpanRef(span.versionId(), span.start(), span.end())).toList());
      var row = retrievedRow(c.id(), c.question(), c.label(), false, covered > 0, covered, spans.size(), latency, false, rank, null, hits);
      if (generate) {
        var facts = c.facts() == null ? List.<String>of() : List.of(c.facts());
        var outcome = chat == null ? EvalGeneration.skipped("未配置对话模型，未生成")
            : EvalGeneration.generate(chat, chatSettings, json, judgeUsed == null ? "llm" : judgeUsed, c.question(), c.expected(), facts,
                trace.candidates().subList(0, Math.min(contextChunks, trace.candidates().size())));
        row = withGeneration(row, outcome);
      }
      results.add(row);
    }
    var summary = summarize(cases.size(), notApplicable, results, !java.util.Objects.equals(indexMarkBefore, indexMark(kbId)));
    var runId = UUID.randomUUID();
    final var modelUsed = model;
    final var chatModel = generate && chat != null ? chat.model() : null;
    final var rerankOn = rerank;
    final var poolUsed = rerankPool;
    tx.executeWithoutResult(status -> {
      try {
        jdbc.update("""
            INSERT INTO eval_run (id, kb_id, k, model, created_by, summary, context_chunks, chunk_max_chars, chunk_overlap, rerank_enabled, rerank_pool, generate, judge, chat_model)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
            """, runId, kbId, k, modelUsed, scope.userId(), json.writeValueAsString(summary), contextChunks, ingestion.chunkMaxChars(),
            ingestion.chunkOverlap(), rerankOn, poolUsed, generate, judgeUsed, chatModel);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
      for (var r : results) {
        jdbc.update("""
            INSERT INTO eval_result (run_id, case_id, mapping_failed, hit, evidence_hit, evidence_total, latency_ms, source_unavailable,
                first_relevant_rank, answer, generation_ms, prompt_tokens, completion_tokens, faithfulness, relevance, failure, citation, judge_reason, retrieved)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """, runId, r.caseId(), r.mappingFailed(), r.hit(), r.evidenceHit(), r.evidenceTotal(), r.latencyMs(), r.sourceUnavailable(),
            r.firstRelevantRank(), r.answer(), r.generationMs(), r.promptTokens(), r.completionTokens(), r.faithfulness(), r.relevance(), r.failure(),
            r.citation(), r.judgeReason(), hitsJson(r.retrieved()));
      }
      audit.record(scope, "EVAL_RUN", "eval-run:" + runId, "OK", Map.of("k", k, "cases", cases.size(), "generate", generate));
    });
    return new RunView(runId, k, contextChunks, ingestion.chunkMaxChars(), ingestion.chunkOverlap(), rerank, rerankPool, generate, judgeUsed, model, chatModel,
        Instant.now(), summary, results);
  }

  private static ResultView retrievedRow(UUID caseId, String question, String label, boolean mappingFailed, boolean hit, int evidenceHit, int evidenceTotal,
      int latencyMs, boolean sourceUnavailable, Integer rank, String failure, List<RetrievedHit> retrieved) {
    return new ResultView(caseId, question, label, mappingFailed, hit, evidenceHit, evidenceTotal, latencyMs, sourceUnavailable, rank, null, null, null, null,
        null, null, failure, null, null, retrieved);
  }

  private static ResultView withGeneration(ResultView base, EvalGeneration.Outcome outcome) {
    return new ResultView(base.caseId(), base.question(), base.label(), base.mappingFailed(), base.hit(), base.evidenceHit(), base.evidenceTotal(),
        base.latencyMs(), base.sourceUnavailable(), base.firstRelevantRank(), outcome.answer(), outcome.generationMs(), outcome.promptTokens(),
        outcome.completionTokens(), outcome.faithfulness(), outcome.relevance(), EvalGeneration.join(base.failure(), outcome.failure()), outcome.citation(),
        outcome.judgeReason(), base.retrieved());
  }

  private String hitsJson(List<RetrievedHit> hits) {
    try {
      return json.writeValueAsString(hits == null ? List.of() : hits);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private int maxContextChunks() {
    return Math.max(1, Math.min(20, chatSettings.maxContextChars() / Math.max(1, chatSettings.maxChunkChars())));
  }

  /** 区间 [from, to) 合起来是否无间隙地覆盖 [start, end)。 */
  static boolean covers(int start, int end, List<int[]> intervals) {
    var sorted = intervals.stream().sorted(java.util.Comparator.comparingInt(iv -> iv[0])).toList();
    int reached = start;
    for (var iv : sorted) {
      if (iv[0] > reached) break;
      reached = Math.max(reached, iv[1]);
      if (reached >= end) return true;
    }
    return false;
  }

  /** 本库最近一次索引任务的完成时间，用来发现运行期间的重建。 */
  private java.sql.Timestamp indexMark(UUID kbId) {
    return jdbc.queryForObject("""
        SELECT max(j.updated_at) FROM ingestion_job j JOIN content_version v ON v.id = j.version_id JOIN document d ON d.id = v.document_id
        WHERE d.kb_id = ? AND j.status = 'succeeded'
        """, java.sql.Timestamp.class, kbId);
  }

  static Summary summarize(int cases, int notApplicable, List<ResultView> results) {
    return summarize(cases, notApplicable, results, false);
  }

  static Summary summarize(int cases, int notApplicable, List<ResultView> results, boolean indexChanged) {
    var evaluated = results.stream().filter(r -> !r.mappingFailed() && !r.sourceUnavailable()).toList();
    int unavailable = (int) results.stream().filter(ResultView::sourceUnavailable).count();
    int hits = (int) evaluated.stream().filter(ResultView::hit).count();
    int covered = evaluated.stream().mapToInt(ResultView::evidenceHit).sum();
    int spans = evaluated.stream().mapToInt(ResultView::evidenceTotal).sum();
    Double macro = evaluated.isEmpty() ? null
        : evaluated.stream().mapToDouble(r -> (double) r.evidenceHit() / r.evidenceTotal()).average().orElse(0);
    var latencies = evaluated.stream().mapToLong(ResultView::latencyMs).toArray();
    var endToEnd = evaluated.stream().filter(r -> r.generationMs() != null).mapToLong(r -> (long) r.latencyMs() + r.generationMs()).toArray();
    var faithfulness = evaluated.stream().map(ResultView::faithfulness).toList();
    return new Summary(cases, evaluated.size(), results.size() - evaluated.size() - unavailable, notApplicable, new Ratio(hits, evaluated.size()),
        new Ratio(covered, spans), macro, EvalMetrics.percentile(latencies, 0.95), unavailable, indexChanged,
        EvalMetrics.mrr(evaluated.stream().map(ResultView::firstRelevantRank).toList()), EvalMetrics.percentile(latencies, 0.50),
        EvalMetrics.mean(faithfulness), EvalMetrics.mean(evaluated.stream().map(ResultView::relevance).toList()),
        EvalMetrics.percentile(endToEnd, 0.50), EvalMetrics.percentile(endToEnd, 0.95), (int) faithfulness.stream().filter(java.util.Objects::nonNull).count());
  }

  @Transactional
  public ImportResult importCases(UUID kbId, String body, String contentType, boolean approve, String requestedName) {
    var scope = access.require(kbId, DEBUG_AND_EVALUATE);
    EvalImport.Batch batch;
    try {
      batch = EvalImport.parse(body, contentType, json);
    } catch (EvalImport.Invalid e) {
      throw ApiException.unprocessable(e.getMessage());
    }
    var name = requestedName == null || requestedName.isBlank() ? batch.name() : requestedName.strip();
    if (name.isBlank() || name.length() > 80 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
      throw ApiException.unprocessable("Dataset name must be 1 to 80 characters");
    }
    var source = (batch.example() ? "example:" : "import:") + name;
    if (source.length() > 100) throw ApiException.unprocessable("Dataset name is too long");
    var ids = new ArrayList<UUID>();
    for (var draft : batch.cases()) {
      var evidence = new ArrayList<EvidenceInput>();
      for (var ref : draft.evidence()) {
        evidence.add(new EvidenceInput(resolveVersion(kbId, ref.documentTitle(), ref.versionLabel()), ref.quote(), null, null));
      }
      ids.add(createCase(kbId, draft.question().strip(), draft.label(), evidence, source, null,
          new Details(draft.expectedAnswer(), draft.facts(), draft.appliesTo(), null)));
    }
    if (approve) {
      for (var id : ids) approve(kbId, id);
    }
    audit.record(scope, "EVAL_CASE_IMPORT", "eval-case:import", "OK", Map.of("cases", ids.size(), "approved", approve ? ids.size() : 0, "example", batch.example()));
    return new ImportResult(ids.size(), approve ? ids.size() : 0, batch.example(), name);
  }

  private UUID resolveVersion(UUID kbId, String title, String versionLabel) {
    var ids = jdbc.query("""
        SELECT v.id FROM content_version v JOIN document d ON d.id = v.document_id
        WHERE d.kb_id = ? AND d.title = ? AND v.version_label = ? AND d.deleted_at IS NULL AND v.withdrawn_at IS NULL
        """, (rs, i) -> rs.getObject(1, UUID.class), kbId, title, versionLabel);
    if (ids.isEmpty()) throw ApiException.unprocessable("Evidence document was not found in this knowledge base: " + title + " " + versionLabel);
    if (ids.size() > 1) throw ApiException.unprocessable("Evidence document title and version are ambiguous: " + title + " " + versionLabel);
    return ids.getFirst();
  }

  @Transactional(readOnly = true)
  public EvalSettings settings(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var chat = models.getIfAvailable();
    return new EvalSettings("fixed-overlap", ingestion.chunkMaxChars(), ingestion.chunkOverlap(), true, "字符二元组 BM25 与向量分加权", retrievalSettings.maxK(),
        chatSettings.contextChunks(), maxContextChunks(), chat != null, chat == null ? null : chat.model());
  }

  @Transactional(readOnly = true)
  public List<RunView> runs(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    return load(kbId, null, 50);
  }

  @Transactional(readOnly = true)
  public RunView get(UUID kbId, UUID runId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var found = load(kbId, runId, 1);
    if (found.isEmpty()) throw ApiException.notFound();
    return found.getFirst();
  }

  @Transactional(readOnly = true)
  RunView latest(UUID kbId) {
    access.require(kbId, DEBUG_AND_EVALUATE);
    var found = load(kbId, null, 1);
    return found.isEmpty() ? null : found.getFirst();
  }

  private static final String RUN_COLUMNS = """
      id, k, model, created_at, summary::text AS summary, context_chunks, chunk_max_chars, chunk_overlap,
      rerank_enabled, rerank_pool, generate, judge, chat_model
      """;

  private List<RunView> load(UUID kbId, UUID runId, int limit) {
    var sql = "SELECT " + RUN_COLUMNS + " FROM eval_run WHERE kb_id = ?" + (runId == null ? "" : " AND id = ?") + " ORDER BY created_at DESC, id DESC LIMIT ?";
    Object[] args = runId == null ? new Object[] {kbId, limit} : new Object[] {kbId, runId, limit};
    return jdbc.query(sql, (rs, i) -> mapRun(rs), args);
  }

  private RunView mapRun(ResultSet rs) throws SQLException {
    var id = rs.getObject("id", UUID.class);
    Summary summary;
    try {
      summary = json.readValue(rs.getString("summary"), Summary.class);
    } catch (JsonProcessingException e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Stored run summary is unreadable");
    }
    return new RunView(id, rs.getInt("k"), (Integer) rs.getObject("context_chunks"), (Integer) rs.getObject("chunk_max_chars"),
        (Integer) rs.getObject("chunk_overlap"), rs.getBoolean("rerank_enabled"), (Integer) rs.getObject("rerank_pool"), rs.getBoolean("generate"),
        rs.getString("judge"), rs.getString("model"), rs.getString("chat_model"), rs.getTimestamp("created_at").toInstant(), summary, resultsOf(id));
  }

  private List<ResultView> resultsOf(UUID runId) {
    return jdbc.query("""
        SELECT r.case_id, c.question, c.label, r.mapping_failed, r.hit, r.evidence_hit, r.evidence_total, r.latency_ms, r.source_unavailable,
               r.first_relevant_rank, r.answer, r.generation_ms, r.prompt_tokens, r.completion_tokens, r.faithfulness, r.relevance, r.failure,
               r.citation, r.judge_reason, r.retrieved::text AS retrieved
        FROM eval_result r JOIN eval_case c ON c.id = r.case_id WHERE r.run_id = ? ORDER BY c.created_at, c.id
        """, (rs, i) -> new ResultView(rs.getObject("case_id", UUID.class), rs.getString("question"), rs.getString("label"), rs.getBoolean("mapping_failed"),
            rs.getBoolean("hit"), rs.getInt("evidence_hit"), rs.getInt("evidence_total"), rs.getInt("latency_ms"), rs.getBoolean("source_unavailable"),
            (Integer) rs.getObject("first_relevant_rank"), rs.getString("answer"), (Integer) rs.getObject("generation_ms"), (Integer) rs.getObject("prompt_tokens"),
            (Integer) rs.getObject("completion_tokens"), number(rs, "faithfulness"), number(rs, "relevance"), rs.getString("failure"), rs.getString("citation"),
            rs.getString("judge_reason"), readHits(rs.getString("retrieved"))), runId);
  }

  private static Double number(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column);
    return value == null ? null : ((Number) value).doubleValue();
  }

  private List<RetrievedHit> readHits(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      return json.readValue(raw, HITS);
    } catch (JsonProcessingException e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Stored retrieval detail is unreadable");
    }
  }
}
