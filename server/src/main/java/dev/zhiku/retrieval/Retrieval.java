package dev.zhiku.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 授权检索的结果类型。阶段状态遵循 PRD 第 11 章：success、failed、skipped、cancelled。 */
public final class Retrieval {
  private Retrieval() {}

  /**
   * score 是当前阶段的排序分；vectorScore 始终是余弦相似度，lexicalScore 是问题二元组在候选（含标题上下文）中的覆盖率，
   * fusedScore / rerankScore 只在对应阶段运行时非空。相关性门槛只看 vectorScore 和 lexicalScore。
   * context 是"文档标题 › 标题路径"，与正文一起参与词法匹配和 embedding。
   */
  public record Candidate(UUID chunkId, UUID documentId, String title, UUID versionId, String versionLabel, int seq,
      String heading, int startOffset, int endOffset, String text, double score, int rank, double vectorScore, String context,
      Double lexicalScore, Double fusedScore, Double rerankScore) {
    public Candidate(UUID chunkId, UUID documentId, String title, UUID versionId, String versionLabel, int seq,
        String heading, int startOffset, int endOffset, String text, double score, int rank) {
      this(chunkId, documentId, title, versionId, versionLabel, seq, heading, startOffset, endOffset, text, score, rank, score, null, null, null, null);
    }

    public Candidate withScore(double newScore, int newRank) {
      return new Candidate(chunkId, documentId, title, versionId, versionLabel, seq, heading, startOffset, endOffset, text, newScore, newRank,
          vectorScore, context, lexicalScore, fusedScore, rerankScore);
    }

    public Candidate withLexical(double coverage) {
      return new Candidate(chunkId, documentId, title, versionId, versionLabel, seq, heading, startOffset, endOffset, text, score, rank,
          vectorScore, context, coverage, fusedScore, rerankScore);
    }

    public Candidate withFused(double fused, int newRank) {
      return new Candidate(chunkId, documentId, title, versionId, versionLabel, seq, heading, startOffset, endOffset, text, fused, newRank,
          vectorScore, context, lexicalScore, fused, rerankScore);
    }

    public Candidate withRerank(double reranked, int newRank) {
      return new Candidate(chunkId, documentId, title, versionId, versionLabel, seq, heading, startOffset, endOffset, text, reranked, newRank,
          vectorScore, context, lexicalScore, fusedScore, reranked);
    }

    /** 参与词法匹配的全文：上下文 + 正文。 */
    public String searchText() {
      return context == null ? text : context + "\n" + text;
    }
  }

  public record Stage(String key, String name, String status, Instant startedAt, Instant endedAt, Long elapsedMs, String summary) {

    public static Stage skipped(String key, String name, String summary) {
      return new Stage(key, name, "skipped", null, null, null, summary);
    }

    public static Stage timed(String key, String name, String status, Instant startedAt, String summary) {
      var ended = Instant.now();
      return new Stage(key, name, status, startedAt, ended, java.time.Duration.between(startedAt, ended).toMillis(), summary);
    }
  }

  /** configVersion 为本次检索采用的知识库配置版本；0 表示部署默认值。 */
  public record Result(UUID traceId, String question, Instant at, String embeddingModel, Integer dimension,
      List<Stage> stages, List<Candidate> candidates, int configVersion) {}

  public record TraceCandidate(int rank, UUID chunkId, UUID versionId, String documentTitle, double score) {}

  /** 存档的轨迹：只有元数据，没有问题和片段正文。 */
  public record TraceView(UUID traceId, String origin, Instant at, int k, String embeddingModel, Integer dimension, UUID messageId,
      Instant createdAt, List<Stage> stages, List<TraceCandidate> candidates, int configVersion) {}
}
