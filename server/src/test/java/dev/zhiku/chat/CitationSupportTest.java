package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitationSupportTest {
  final List<String> sources = List.of("司龄满十年的员工每年可以休假十五天。", "差旅报销须在出差结束后 30 日内提交。");

  @Test
  void sentencesBackedByTheirCitedSourcePass() {
    var r = CitationSupport.check("司龄满十年每年休假十五天[1]。报销要在出差结束后 30 日内提交[2]。", sources);
    assertThat(r.checked()).isEqualTo(2);
    assertThat(r.allSupported()).isTrue();
  }

  @Test
  void sentenceCitingTheWrongSourceIsWeak() {
    var r = CitationSupport.check("司龄满十年每年休假十五天[2]。", sources);
    assertThat(r.supported()).isZero();
    assertThat(r.weak()).containsExactly(1);
  }

  @Test
  void markerAfterThePeriodBelongsToThePreviousSentence() {
    assertThat(CitationSupport.sentences("十五天。[1]另外，三十日。[2]")).containsExactly("十五天。[1]", "另外，三十日。[2]");
    assertThat(CitationSupport.cited("a[2]b[1][2]")).containsExactly(1, 2);
  }

  @Test
  void sentencesWithoutMarkersAreNotChecked() {
    assertThat(CitationSupport.check("资料中没有足够依据。", sources).checked()).isZero();
  }
}
