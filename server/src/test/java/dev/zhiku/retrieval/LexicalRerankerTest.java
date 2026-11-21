package dev.zhiku.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.retrieval.Retrieval.Candidate;
import dev.zhiku.support.TestEmbeddingProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LexicalRerankerTest {
  @Test
  void exactPhraseOutranksAVectorNeighborThatDoesNotShareTerms() {
    var query = "报销需要三张发票";
    var vectorFirst = candidate("无关近邻", "年假年假年假年假", 0.99, 1);
    var exact = candidate("报销办法", "报销需要三张发票。请附清单。", 0.40, 2);
    var ranked = LexicalReranker.rerank(query, List.of(vectorFirst, exact), 1);
    assertThat(ranked).hasSize(1);
    assertThat(ranked.getFirst().title()).isEqualTo("报销办法");
    assertThat(ranked.getFirst().rank()).isEqualTo(1);
  }

  @Test
  void keepsVectorOrderWhenNothingSharesTermsAndHonorsTheKeepLimit() {
    var query = "报销需要三张发票";
    var first = candidate("甲", "差旅住宿标准", 0.9, 1);
    var second = candidate("乙", "年假天数说明", 0.8, 2);
    var ranked = LexicalReranker.rerank(query, List.of(first, second), 1);
    assertThat(ranked).singleElement().extracting(Candidate::title).isEqualTo("甲");
    assertThat(LexicalReranker.rerank(query, List.of(), 3)).isEmpty();
    assertThat(LexicalReranker.bigrams("  ")).isEmpty();
    assertThat(LexicalReranker.bigrams("甲")).isEmpty();
  }

  @Test
  void repeatedPhraseOutranksTheDilutedGoldDocumentInHashSpace() {
    var embeddings = new TestEmbeddingProvider();
    var query = embeddings.embed(List.of("年假申请窗口")).getFirst();
    var repeated = embeddings.embed(List.of("年假申请窗口".repeat(6))).getFirst();
    var diluted = embeddings.embed(List.of("年假申请窗口。司龄满十年的员工每年可以休假十五天。")).getFirst();
    assertThat(cosine(query, repeated)).isGreaterThan(cosine(query, diluted));
  }

  @Test
  void keywordStuffingDoesNotOutrankTheDocumentThatAnswers() {
    var query = "年假申请要提前几天";
    var stuffed = candidate("年假窗口说明", "年假申请窗口".repeat(6) + "。此页只说明申请入口名称，没有天数。", 0.43, 1);
    var gold = candidate("年假办法", "司龄满十年的员工每年可以休假十五天。申请须提前三个工作日。", 0.29, 2);
    var ranked = LexicalReranker.rerank(query, List.of(stuffed, gold), 2);
    assertThat(ranked.getFirst().title()).isEqualTo("年假办法");
    assertThat(LexicalReranker.stuffing(Bigrams.all("年假申请窗口".repeat(6)))).isLessThan(0.3);
    assertThat(LexicalReranker.stuffing(Bigrams.all("司龄满十年的员工每年可以休假十五天"))).isEqualTo(1.0);
  }

  private static double cosine(float[] left, float[] right) {
    double dot = 0;
    double a = 0;
    double b = 0;
    for (int i = 0; i < left.length; i++) {
      dot += left[i] * right[i];
      a += left[i] * left[i];
      b += right[i] * right[i];
    }
    return dot / Math.sqrt(a * b);
  }

  private static Candidate candidate(String title, String text, double score, int rank) {
    return new Candidate(UUID.randomUUID(), UUID.randomUUID(), title, UUID.randomUUID(), "v1", 0, null, 0, text.length(), text, score, rank);
  }
}
