package dev.zhiku.support;

import dev.zhiku.chat.ChatModel;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** 仅用于测试的脚本化聊天模型：将预设回答分成小片段输出，并可在结束前运行钩子。 */
public class TestChatModel implements ChatModel {
  public volatile Function<List<Message>, String> script = messages -> "年假按司龄计算[1]。";
  public volatile Runnable beforeFinish = () -> {};
  public volatile boolean failMidway = false;
  /** 在第 n 个片段（从 0 开始）交给调用方之后运行，例如在流中途撤销访问权限。 */
  public volatile java.util.function.IntConsumer afterPiece = n -> {};
  public volatile List<Message> lastPrompt = List.of();
  /** 非空时 complete 原样返回；默认 null，表示这个替身不提供 token 用量。 */
  public volatile Integer promptTokens = null;
  public volatile Integer completionTokens = null;

  public void reset() {
    script = messages -> "年假按司龄计算[1]。";
    beforeFinish = () -> {};
    failMidway = false;
    afterPiece = n -> {};
    lastPrompt = List.of();
    promptTokens = null;
    completionTokens = null;
  }

  @Override
  public String model() {
    return "test-scripted";
  }

  @Override
  public Completion complete(List<Message> messages) {
    var text = new StringBuilder();
    stream(messages, text::append, () -> false);
    return new Completion(text.toString(), promptTokens, completionTokens);
  }

  @Override
  public void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled) {
    lastPrompt = messages;
    var answer = script.apply(messages);
    for (int i = 0; i < answer.length(); i += 4) {
      if (cancelled.getAsBoolean()) return;
      onToken.accept(answer.substring(i, Math.min(answer.length(), i + 4)));
      afterPiece.accept(i / 4);
      if (failMidway && i > 0) throw new IllegalStateException("model failed (test)");
    }
    beforeFinish.run();
  }
}
