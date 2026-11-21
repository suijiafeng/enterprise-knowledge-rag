package dev.zhiku.retrieval;

import dev.zhiku.retrieval.Retrieval.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 候选池内的轻量重排：字符二元组 BM25（候选的标题上下文与正文一起计）归一化后与向量相似度加权融合。
 * 词法权重 {@link #LEXICAL_WEIGHT}。没有任何查询二元组命中时保持向量顺序。
 * 二元组重复率过高（关键词堆砌）的候选按 {@link #stuffing} 降低词法分，避免靠重复关键词挤到前面。
 * 不调用外部模型，也不是交叉编码器。
 */
public final class LexicalReranker {
  static final double K1 = 1.2;
  static final double B = 0.75;
  /** 词法比向量重，这样精确短语可以压过只在向量空间里靠近的候选。 */
  static final double LEXICAL_WEIGHT = 0.65;

  /** 不同二元组占比低于此值视为堆砌；正常中文段落一般在 0.85 以上。 */
  static final double MIN_DIVERSITY = 0.85;

  private LexicalReranker() {}

  /** 词法分系数：(不同二元组数 / 二元组总数 / MIN_DIVERSITY)³，上限 1。 */
  static double stuffing(List<String> bigrams) {
    if (bigrams.isEmpty()) return 1;
    double diversity = (double) new HashSet<>(bigrams).size() / bigrams.size();
    double ratio = Math.min(1, diversity / MIN_DIVERSITY);
    return ratio * ratio * ratio;
  }

  public static List<Candidate> rerank(String query, List<Candidate> pool, int keep) {
    if (pool.isEmpty() || keep < 1) return List.of();
    var terms = Bigrams.all(query);
    var docs = new ArrayList<List<String>>(pool.size());
    for (var candidate : pool) docs.add(Bigrams.all(candidate.searchText()));
    var bm25 = bm25(terms, docs);
    for (int i = 0; i < bm25.length; i++) bm25[i] *= stuffing(docs.get(i));
    boolean any = false;
    for (var score : bm25) {
      if (score > 0) any = true;
    }
    if (!any) return copy(pool, Math.min(keep, pool.size()));
    double maxBm25 = 0;
    double minVector = Double.POSITIVE_INFINITY;
    double maxVector = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < pool.size(); i++) {
      maxBm25 = Math.max(maxBm25, bm25[i]);
      minVector = Math.min(minVector, pool.get(i).vectorScore());
      maxVector = Math.max(maxVector, pool.get(i).vectorScore());
    }
    var fused = new double[pool.size()];
    for (int i = 0; i < pool.size(); i++) {
      double vector = maxVector == minVector ? 1 : (pool.get(i).vectorScore() - minVector) / (maxVector - minVector);
      fused[i] = (1 - LEXICAL_WEIGHT) * vector + LEXICAL_WEIGHT * (bm25[i] / maxBm25);
    }
    var order = new ArrayList<Integer>();
    for (int i = 0; i < pool.size(); i++) order.add(i);
    order.sort(Comparator.<Integer>comparingDouble(i -> fused[i]).reversed().thenComparingInt(i -> i));
    var ranked = new ArrayList<Candidate>();
    int limit = Math.min(keep, order.size());
    for (int place = 0; place < limit; place++) {
      int index = order.get(place);
      ranked.add(pool.get(index).withRerank(fused[index], place + 1));
    }
    return List.copyOf(ranked);
  }

  static List<String> bigrams(String text) {
    return Bigrams.all(text);
  }

  private static double[] bm25(List<String> query, List<List<String>> docs) {
    var scores = new double[docs.size()];
    if (query.isEmpty()) return scores;
    double avg = docs.stream().mapToInt(List::size).average().orElse(1);
    var df = new HashMap<String, Integer>();
    for (var doc : docs) {
      for (var term : new HashSet<>(doc)) df.merge(term, 1, Integer::sum);
    }
    int n = docs.size();
    for (int i = 0; i < n; i++) {
      var tf = new HashMap<String, Integer>();
      for (var term : docs.get(i)) tf.merge(term, 1, Integer::sum);
      int length = Math.max(docs.get(i).size(), 1);
      double score = 0;
      for (var term : query) {
        int freq = tf.getOrDefault(term, 0);
        if (freq == 0) continue;
        int docsWith = df.getOrDefault(term, 0);
        double idf = Math.log(1 + (n - docsWith + 0.5) / (docsWith + 0.5));
        double denom = freq + K1 * (1 - B + B * length / avg);
        score += idf * freq * (K1 + 1) / denom;
      }
      scores[i] = score;
    }
    return scores;
  }

  private static List<Candidate> copy(List<Candidate> pool, int keep) {
    var out = new ArrayList<Candidate>();
    for (int i = 0; i < keep; i++) out.add(pool.get(i).withScore(pool.get(i).score(), i + 1));
    return List.copyOf(out);
  }
}
