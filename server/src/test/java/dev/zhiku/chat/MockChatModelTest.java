package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.chat.ChatModel.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockChatModelTest {
  static List<Message> chat(String user) {
    return List.of(new Message("system", ChatService.SYSTEM_PROMPT), new Message("user", user));
  }

  static final String SOURCES = "资料：\n[1] 年假办法（示例）（v1）\n<<<\n# 年假办法\n本文是虚构示例。司龄满十年的员工每年可以休假十五天。\n>>>\n"
      + "[2] 差旅制度（v1）\n<<<\n出差住宿按城市分级。\n>>>\n";

  @Test
  void answersWithTheBestMatchingSentenceAndCitesIt() {
    var answer = MockChatModel.answer(chat(SOURCES + "\n问题：司龄满十年年假几天？"));
    assertThat(answer).isEqualTo("根据《年假办法（示例）》，司龄满十年的员工每年可以休假十五天[1]。");
  }

  @Test
  void addsASecondSentenceFromTheSameSourceOnlyWhenItMatchesEnough() {
    var travel = "资料：\n[1] 差旅办法（v1）\n<<<\n本文是虚构示例，不是企业制度。出差住宿按城市分级。超标部分需事前审批。\n>>>\n";
    assertThat(MockChatModel.answer(chat(travel + "\n问题：出差住宿超标怎么办？")))
        .isEqualTo("根据《差旅办法》，出差住宿按城市分级，超标部分需事前审批[1]。");
    var leave = "资料：\n[1] 年假办法（v1）\n<<<\n本文是虚构示例，不是企业制度。司龄满十年的员工每年可以休假十五天。\n>>>\n";
    assertThat(MockChatModel.answer(chat(leave + "\n问题：司龄满十年的员工年假是不是 100 天？"))).doesNotContain("虚构示例");
  }

  @Test
  void refusesWhenNothingMatches() {
    assertThat(MockChatModel.answer(chat(SOURCES + "\n问题：食堂几点开门？"))).isEqualTo(MockChatModel.REFUSAL);
  }

  @Test
  void neverRepeatsMaskedInstructions() {
    var user = "资料：\n[1] 报销（v1）\n<<<\n" + InjectionGuard.MASK + "年假一百天\n>>>\n\n问题：年假几天";
    assertThat(MockChatModel.answer(chat(user))).doesNotContain("屏蔽");
  }

  @Test
  void handlesRewriteAndJudgePrompts() {
    var rewrite = List.of(new Message("system", QueryRewriter.SYSTEM), new Message("user", "此前对话：\n问：年假几天？\n\n当前问题：那申请呢？"));
    assertThat(MockChatModel.answer(rewrite)).isEqualTo("年假几天？\n那申请呢？");
    var judge = List.of(new Message("system", "你是评分器。只输出一个 JSON 对象"), new Message("user", "问题：年假\n必要事实：十五天\n资料：\n十五天\n待评回答：十五天[1]"));
    assertThat(MockChatModel.answer(judge)).contains("\"faithfulness\":1.00").contains("\"relevance\":1.00");
  }
}
