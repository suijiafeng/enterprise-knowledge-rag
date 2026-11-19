package dev.zhiku.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvalMetricsTest {
  @Test
  void percentileUsesNearestRankAndIsEmptySafe() {
    assertThat(EvalMetrics.percentile(new long[0], 0.95)).isNull();
    assertThat(EvalMetrics.percentile(new long[] {5}, 0.50)).isEqualTo(5L);
    assertThat(EvalMetrics.percentile(new long[] {5}, 0.95)).isEqualTo(5L);
    assertThat(EvalMetrics.percentile(new long[] {4, 1, 3, 2}, 0.50)).isEqualTo(2L);
    assertThat(EvalMetrics.percentile(new long[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20}, 0.95)).isEqualTo(19L);
    assertThatThrownBy(() -> EvalMetrics.percentile(new long[] {1}, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EvalMetrics.percentile(new long[] {1}, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mrrTreatsMissesAsZeroAndAnEmptySetAsUnknown() {
    assertThat(EvalMetrics.mrr(List.of())).isNull();
    assertThat(EvalMetrics.mrr(java.util.Arrays.asList((Integer) null))).isEqualTo(0d);
    assertThat(EvalMetrics.mrr(List.of(1))).isEqualTo(1d);
    assertThat(EvalMetrics.mrr(java.util.Arrays.asList(1, null))).isEqualTo(0.5d);
    assertThat(EvalMetrics.mrr(List.of(2, 4))).isEqualTo(0.375d);
    assertThat(EvalMetrics.mrr(List.of(0, -1))).isEqualTo(0d);
  }

  @Test
  void meanSkipsMissingScores() {
    assertThat(EvalMetrics.mean(List.of())).isNull();
    assertThat(EvalMetrics.mean(java.util.Arrays.asList(null, null))).isNull();
    assertThat(EvalMetrics.mean(java.util.Arrays.asList(1d, null, 0d))).isEqualTo(0.5d);
  }

  @Test
  void firstRelevantRankUsesHalfOpenOverlapOnTheSameVersion() {
    var version = UUID.randomUUID();
    var other = UUID.randomUUID();
    var spans = List.of(new EvalMetrics.SpanRef(version, 10, 20));
    assertThat(EvalMetrics.firstRelevantRank(List.of(), spans)).isNull();
    assertThat(EvalMetrics.firstRelevantRank(List.of(new EvalMetrics.ChunkRef(version, 20, 30, 1)), spans)).isNull();
    assertThat(EvalMetrics.firstRelevantRank(List.of(new EvalMetrics.ChunkRef(version, 0, 10, 1)), spans)).isNull();
    assertThat(EvalMetrics.firstRelevantRank(List.of(new EvalMetrics.ChunkRef(other, 10, 20, 1)), spans)).isNull();
    assertThat(EvalMetrics.firstRelevantRank(List.of(
        new EvalMetrics.ChunkRef(version, 0, 12, 2),
        new EvalMetrics.ChunkRef(version, 18, 40, 1)), spans)).isEqualTo(1);
    assertThat(EvalMetrics.firstRelevantRank(List.of(new EvalMetrics.ChunkRef(version, 12, 18, 3)), spans)).isEqualTo(3);
  }

  @Test
  void summaryExcludesPreparationAndUnavailableCasesAndDoesNotInventGenerationScores() {
    var missed = row(false, false, false, 0, 1, 10, null, null, null, null);
    var hit = row(false, false, true, 1, 1, 30, 2, 20, 1d, null);
    var prep = row(true, false, false, 0, 1, 0, null, null, null, null);
    var gone = row(false, true, false, 0, 1, 0, null, null, 1d, 1d);
    var summary = EvaluationService.summarize(4, 1, List.of(missed, hit, prep, gone), false);
    assertThat(summary.evaluated()).isEqualTo(2);
    assertThat(summary.prepFailed()).isEqualTo(1);
    assertThat(summary.sourceUnavailable()).isEqualTo(1);
    assertThat(summary.notApplicable()).isEqualTo(1);
    assertThat(summary.hit()).isEqualTo(new EvaluationService.Ratio(1, 2));
    assertThat(summary.recall()).isEqualTo(new EvaluationService.Ratio(1, 2));
    assertThat(summary.recallMacro()).isEqualTo(0.5d);
    assertThat(summary.mrr()).isEqualTo(0.25d);
    assertThat(summary.p50Ms()).isEqualTo(10L);
    assertThat(summary.p95Ms()).isEqualTo(30L);
    assertThat(summary.faithfulness()).isEqualTo(1d);
    assertThat(summary.relevance()).isNull();
    assertThat(summary.judged()).isEqualTo(1);
    assertThat(summary.p50E2eMs()).isEqualTo(50L);
    assertThat(summary.p95E2eMs()).isEqualTo(50L);
    var none = EvaluationService.summarize(0, 0, List.of(), true);
    assertThat(none.mrr()).isNull();
    assertThat(none.p50Ms()).isNull();
    assertThat(none.p95Ms()).isNull();
    assertThat(none.faithfulness()).isNull();
    assertThat(none.p50E2eMs()).isNull();
    assertThat(none.hit().den()).isZero();
    assertThat(none.indexChangedDuringRun()).isTrue();
  }

  private static EvaluationService.ResultView row(boolean mappingFailed, boolean unavailable, boolean hit, int evidenceHit, int evidenceTotal, int latency,
      Integer rank, Integer generationMs, Double faithfulness, Double relevance) {
    return new EvaluationService.ResultView(UUID.randomUUID(), "q", "answerable", mappingFailed, hit, evidenceHit, evidenceTotal, latency, unavailable,
        rank, null, generationMs, null, null, faithfulness, relevance, null, null, null, List.of());
  }
}
