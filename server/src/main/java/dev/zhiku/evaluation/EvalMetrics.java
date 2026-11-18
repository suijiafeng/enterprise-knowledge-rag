package dev.zhiku.evaluation;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 评测指标的纯计算，不访问数据库，也不补造缺失样本。
 *
 * <p>命中率 Hit@K：计入分母的用例里，至少一条期望片段被 top-K 切块无间隙完整覆盖的比例。
 * 来源失效、无法映射到当前切块的用例不进分母。完整覆盖沿用 {@code EvaluationService.covers}。
 *
 * <p>Recall@K：被完整覆盖的期望片段数 / 期望片段总数。宏平均是各题覆盖率的算术平均。
 *
 * <p>MRR：每题取 top-K 中第一块与任一期望片段相交的名次的倒数，没有相交记 0，再取平均。
 * 相交指同一内容版本上的半开区间 [start, end) 重叠。相交不等于完整覆盖，因此 MRR 可以高于命中率。
 *
 * <p>P50/P95：最近秩、向上取整，排序后取下标 ceil(q * n) - 1。检索延迟与端到端延迟分开；
 * 没有生成耗时的用例不进入端到端分母。没有样本时百分位和平均值都是 null，不写成 0。
 */
public final class EvalMetrics {
  private EvalMetrics() {}

  public record ChunkRef(UUID versionId, int start, int end, int rank) {}

  public record SpanRef(UUID versionId, int start, int end) {}

  public static boolean overlaps(int start, int end, int otherStart, int otherEnd) {
    return start < otherEnd && otherStart < end;
  }

  /** 没有相交的切块时返回 null。名次用检索给出的 rank，不使用列表下标。 */
  public static Integer firstRelevantRank(List<ChunkRef> chunks, List<SpanRef> spans) {
    Integer best = null;
    for (var chunk : chunks) {
      for (var span : spans) {
        if (chunk.versionId().equals(span.versionId()) && overlaps(chunk.start(), chunk.end(), span.start(), span.end())
            && (best == null || chunk.rank() < best)) {
          best = chunk.rank();
        }
      }
    }
    return best;
  }

  public static Long percentile(long[] values, double q) {
    if (values.length == 0) return null;
    if (Double.isNaN(q) || q <= 0 || q > 1) throw new IllegalArgumentException("q must be in (0, 1]");
    var sorted = values.clone();
    Arrays.sort(sorted);
    int index = (int) Math.ceil(q * sorted.length) - 1;
    if (index < 0) index = 0;
    return sorted[index];
  }

  /** null 或非正名次记 0。列表为空返回 null。 */
  public static Double mrr(List<Integer> firstRelevantRanks) {
    if (firstRelevantRanks.isEmpty()) return null;
    double sum = 0;
    for (var rank : firstRelevantRanks) {
      if (rank != null && rank > 0) sum += 1.0 / rank;
    }
    return sum / firstRelevantRanks.size();
  }

  /** 跳过 null。没有可用数值时返回 null。 */
  public static Double mean(List<Double> values) {
    double sum = 0;
    int n = 0;
    for (var value : values) {
      if (value == null) continue;
      sum += value;
      n++;
    }
    return n == 0 ? null : sum / n;
  }
}
