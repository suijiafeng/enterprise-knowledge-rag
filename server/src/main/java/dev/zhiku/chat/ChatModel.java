package dev.zhiku.chat;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 流式聊天模型的端口。实现必须有超时、取消时停止，且绝不记录内容。 */
public interface ChatModel {
  record Message(String role, String content) {}

  /** promptTokens / completionTokens 仅在提供方返回用量时非空；不知道就保持 null，不估算。 */
  record Completion(String text, Integer promptTokens, Integer completionTokens) {}

  String model();

  void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled);

  /** 评测等非流式调用。默认实现不报告 token。 */
  default Completion complete(List<Message> messages) {
    var text = new StringBuilder();
    stream(messages, text::append, () -> false);
    return new Completion(text.toString(), null, null);
  }
}
