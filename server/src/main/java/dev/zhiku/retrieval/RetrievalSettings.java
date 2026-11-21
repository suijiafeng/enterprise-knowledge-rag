package dev.zhiku.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * minScore / minLexicalCoverage 组成相关性门槛：候选的向量相似度达到 minScore，或问题的去重二元组
 * 至少有 minLexicalCoverage 的比例出现在候选（含标题上下文）中，才可送入模型；两者都不满足即拒答。
 * 阈值与 embedding 模型相关，需用真实评测集校准。annMinChunks：合格 chunk 数达到该值才走 HNSW 近似检索。
 */
@ConfigurationProperties("zhiku.retrieval")
public record RetrievalSettings(int defaultK, int maxK, Double minScore, Double minLexicalCoverage, Boolean hybrid, Boolean rerank,
    Integer rerankPool, Integer annMinChunks) {
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public RetrievalSettings {
    if (minScore == null) minScore = 0.45;
    if (minLexicalCoverage == null) minLexicalCoverage = 0.35;
    if (hybrid == null) hybrid = true;
    if (rerank == null) rerank = true;
    if (rerankPool == null) rerankPool = 20;
    if (annMinChunks == null) annMinChunks = 5000;
  }

  public RetrievalSettings(int defaultK, int maxK) {
    this(defaultK, maxK, null, null, null, null, null, null);
  }
}
