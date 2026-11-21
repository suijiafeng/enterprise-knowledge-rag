package dev.zhiku.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MockEmbeddingProviderTest {
  static double cosine(float[] a, float[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
    return dot;
  }

  @Test
  void deterministicUnitVectorsThatReflectCharacterOverlap() {
    var p = new MockEmbeddingProvider();
    var v = p.embed(List.of("司龄满十年年假几天", "司龄满十年的员工每年可以休假十五天", "公司食堂几点开门", "单"));
    assertThat(v).allSatisfy(x -> assertThat(x).hasSize(MockEmbeddingProvider.DIMENSION));
    assertThat(EmbeddingVectors.validate(v, 4)).isEqualTo(256);
    assertThat(cosine(v.get(0), v.get(0))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(cosine(v.get(0), v.get(1))).isGreaterThan(cosine(v.get(0), v.get(2)));
    assertThat(p.embed(List.of("司龄满十年年假几天")).getFirst()).containsExactly(v.get(0));
  }
}
