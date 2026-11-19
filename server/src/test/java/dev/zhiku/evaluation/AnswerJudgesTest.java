package dev.zhiku.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.chat.ChatModel;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AnswerJudgesTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void simulatedJudgeScoresLexicalOverlapAndRefusesToScoreWithoutFacts() {
    var supported = AnswerJudges.simulated("司龄满十年的员工每年可以休假十五天。", List.of("司龄满十年的员工每年可以休假十五天"), "资料含有司龄满十年的员工每年可以休假十五天。");
    assertThat(supported.faithfulness()).isEqualTo(1d);
    assertThat(supported.relevance()).isEqualTo(1d);
    assertThat(supported.failure()).isNull();

    var invented = AnswerJudges.simulated("司龄满十年的员工每年可以休假十五天。", List.of("司龄满十年的员工每年可以休假十五天"), "此页没有天数。");
    assertThat(invented.faithfulness()).isEqualTo(0d);
    assertThat(invented.relevance()).isEqualTo(1d);

    var partial = AnswerJudges.simulated("只有第一条。", List.of("只有第一条", "第二条不在"), "只有第一条。");
    assertThat(partial.relevance()).isEqualTo(0.5d);
    assertThat(partial.faithfulness()).isEqualTo(1d);

    var refusal = AnswerJudges.simulated("资料中没有足够依据。", List.of("十五条"), "十五条");
    assertThat(refusal.faithfulness()).isEqualTo(1d);
    assertThat(refusal.relevance()).isEqualTo(0d);

    var blank = AnswerJudges.simulated("  ", List.of("事实"), "事实");
    assertThat(blank.faithfulness()).isEqualTo(0d);
    assertThat(blank.relevance()).isEqualTo(0d);

    var noFacts = AnswerJudges.simulated("任意回答", List.of(), "资料");
    assertThat(noFacts.faithfulness()).isNull();
    assertThat(noFacts.relevance()).isNull();
    assertThat(noFacts.failure()).contains("没有必要事实");
  }

  @Test
  void llmParserAcceptsJsonAndDropsUnreadableScores() {
    var parsed = AnswerJudges.parse(json, "```json\n{\"faithfulness\":0.25,\"relevance\":1,\"reason\":\"覆盖了事实\"}\n```");
    assertThat(parsed.faithfulness()).isEqualTo(0.25d);
    assertThat(parsed.relevance()).isEqualTo(1d);
    assertThat(parsed.reason()).isEqualTo("覆盖了事实");
    for (var raw : List.of("不是 json", "{\"faithfulness\":1.2,\"relevance\":0}", "{\"faithfulness\":\"高\",\"relevance\":1}", "", "{\"relevance\":1}")) {
      var rejected = AnswerJudges.parse(json, raw);
      assertThat(rejected.faithfulness()).isNull();
      assertThat(rejected.relevance()).isNull();
      assertThat(rejected.failure()).isEqualTo("评审输出无法解析");
    }
  }

  @Test
  void llmJudgeRecordsAFailureWhenTheModelThrows() {
    ChatModel broken = new ChatModel() {
      public String model() {
        return "broken";
      }

      public void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled) {
        throw new IllegalStateException("down");
      }
    };
    var judgment = AnswerJudges.llm(broken, json, "问题", "答案", List.of("事实"), "资料", "回答");
    assertThat(judgment.faithfulness()).isNull();
    assertThat(judgment.failure()).isEqualTo("评审调用失败");
  }
}
