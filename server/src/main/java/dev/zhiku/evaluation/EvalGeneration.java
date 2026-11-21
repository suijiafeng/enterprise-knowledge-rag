package dev.zhiku.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.chat.ChatModel;
import dev.zhiku.chat.ChatService;
import dev.zhiku.chat.ChatSettings;
import dev.zhiku.chat.CitationCheck;
import dev.zhiku.retrieval.Retrieval.Candidate;
import java.util.List;

/** 评测里的一次性生成：不写会话、不保存问答消息。提示词与问答使用同一条系统提示。 */
final class EvalGeneration {
  private EvalGeneration() {}

  record Outcome(String answer, Integer generationMs, Integer promptTokens, Integer completionTokens, String citation, String failure,
      Double faithfulness, Double relevance, String judgeReason) {}

  static Outcome skipped(String failure) {
    return new Outcome(null, null, null, null, null, failure, null, null, null);
  }

  static Outcome generate(ChatModel model, ChatSettings settings, ObjectMapper json, String judge, String question, String expected,
      List<String> facts, List<Candidate> context) {
    if (context.isEmpty()) return skipped("没有可交给模型的片段，未生成");
    var passages = new StringBuilder("资料：\n");
    for (int i = 0; i < context.size(); i++) {
      var candidate = context.get(i);
      var body = candidate.text() == null ? "" : candidate.text();
      if (body.length() > settings.maxChunkChars()) body = body.substring(0, settings.maxChunkChars());
      // 与问答同一套资料处理：疑似指令先屏蔽。
      body = dev.zhiku.chat.InjectionGuard.sanitize(body).text();
      passages.append('[').append(i + 1).append("] ").append(candidate.title()).append("（").append(candidate.versionLabel())
          .append("）\n<<<\n").append(body.replace("<<<", "‹‹‹").replace(">>>", "›››")).append("\n>>>\n");
    }
    var user = passages + "\n问题：" + question;
    var messages = List.of(new ChatModel.Message("system", ChatService.SYSTEM_PROMPT), new ChatModel.Message("user", user));
    if (messages.stream().mapToInt(message -> message.content().length()).sum() > settings.maxContextChars()) {
      return skipped("入模内容超出上限，未生成");
    }
    long started = System.nanoTime();
    ChatModel.Completion completion;
    try {
      completion = model.complete(messages);
    } catch (RuntimeException e) {
      return skipped("生成失败");
    }
    int generationMs = (int) ((System.nanoTime() - started) / 1_000_000);
    var text = completion.text() == null ? "" : completion.text();
    if (text.length() > settings.maxAnswerChars()) {
      return new Outcome(null, generationMs, completion.promptTokens(), completion.completionTokens(), null, "回答超出长度上限，已丢弃", null, null, null);
    }
    var citation = switch (CitationCheck.check(text, context.size())) {
      case OK -> "ok";
      case NONE -> "none";
      case OUT_OF_RANGE -> "out-of-range";
    };
    var citationFailure = switch (citation) {
      case "none" -> "回答没有可核对的引用";
      case "out-of-range" -> "回答引用了不存在的来源";
      default -> null;
    };
    var judgment = "simulated".equals(judge)
        ? AnswerJudges.simulated(text, facts, passages.toString())
        : AnswerJudges.llm(model, json, question, expected, facts, passages.toString(), text);
    return new Outcome(text, generationMs, completion.promptTokens(), completion.completionTokens(), citation,
        join(citationFailure, judgment.failure()), judgment.faithfulness(), judgment.relevance(), judgment.reason());
  }

  static String join(String left, String right) {
    if (left == null || left.isBlank()) return right == null || right.isBlank() ? null : right;
    if (right == null || right.isBlank()) return left;
    return left + "；" + right;
  }
}
