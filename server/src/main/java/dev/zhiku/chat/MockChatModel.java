package dev.zhiku.chat;

import dev.zhiku.evaluation.AnswerJudges;
import dev.zhiku.retrieval.Bigrams;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地演示与 CI 用的抽取式模型（zhiku.chat.provider=mock），不调用任何外部服务：
 * 从编号资料里挑出与问题字面重合最多的句子原样作答并标注 [n]；重合不足时回答"资料中没有足够依据"。
 * 也能响应查询改写（按规则）和评分（按词面规则）。它不理解语义，回答质量不代表真实模型。
 */
@Component
@ConditionalOnProperty(name = "zhiku.chat.provider", havingValue = "mock")
public class MockChatModel implements ChatModel {
  static final String REFUSAL = "资料中没有足够依据。";
  private static final Pattern SOURCE = Pattern.compile("\\[(\\d+)\\] (.*?)（[^（）\\n]*）\\n<<<\\n(.*?)\\n>>>", Pattern.DOTALL);
  private static final Pattern FIELD = Pattern.compile("^(问题|必要事实|待评回答)：(.*)$", Pattern.MULTILINE);
  /** 至少这么多个问题二元组命中，才算资料里有答案。 */
  static final int MIN_OVERLAP = 2;

  @Override
  public String model() {
    return "mock:extractive";
  }

  @Override
  public void stream(List<Message> messages, Consumer<String> onToken, BooleanSupplier cancelled) {
    var answer = answer(messages);
    for (int i = 0; i < answer.length(); i += 4) {
      if (cancelled.getAsBoolean()) return;
      onToken.accept(answer.substring(i, Math.min(answer.length(), i + 4)));
      try {
        Thread.sleep(15);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  static String answer(List<Message> messages) {
    var system = messages.isEmpty() ? "" : messages.getFirst().content();
    var user = messages.isEmpty() ? "" : messages.getLast().content();
    if (system.startsWith(QueryRewriter.SYSTEM.substring(0, 8))) return rewrite(user);
    if (system.contains("你是评分器")) return judge(user);
    return extract(user);
  }

  static String rewrite(String user) {
    var current = after(user, "当前问题：");
    String previous = null;
    for (var line : user.lines().toList()) if (line.startsWith("问：")) previous = line.substring(2);
    return QueryRewriter.heuristic(previous, current);
  }

  static String judge(String user) {
    String answer = "";
    List<String> facts = List.of();
    var m = FIELD.matcher(user);
    while (m.find()) {
      if (m.group(1).equals("待评回答")) answer = m.group(2);
      if (m.group(1).equals("必要事实") && !m.group(2).equals("（无）")) facts = Arrays.asList(m.group(2).split("；"));
    }
    var j = AnswerJudges.simulated(answer, facts, user);
    if (j.faithfulness() == null) return "{\"faithfulness\":0,\"relevance\":0,\"reason\":\"没有必要事实\"}";
    return String.format(Locale.ROOT, "{\"faithfulness\":%.2f,\"relevance\":%.2f,\"reason\":\"模拟评分（词面）\"}", j.faithfulness(), j.relevance());
  }

  private record Pick(int source, String title, String sentence, int overlap) {}

  static String extract(String user) {
    var question = after(user, "\n问题：");
    var terms = Bigrams.distinct(question);
    var picks = new ArrayList<Pick>();
    var m = SOURCE.matcher(user);
    while (m.find()) {
      int n = Integer.parseInt(m.group(1));
      Pick best = null;
      Pick next = null;
      for (var sentence : m.group(3).split("(?<=[。！？!?])|\\n")) {
        var s = sentence.strip();
        if (s.isEmpty() || s.startsWith("#") || s.contains(InjectionGuard.MASK)) continue;
        int overlap = 0;
        var own = Bigrams.distinct(s);
        for (var t : terms) if (own.contains(t)) overlap++;
        var pick = new Pick(n, m.group(2).strip(), s, overlap);
        if (best == null || overlap > best.overlap()) {
          next = best;
          best = pick;
        } else if (next == null || overlap > next.overlap()) {
          next = pick;
        }
      }
      if (best != null && best.overlap() >= MIN_OVERLAP) {
        // 同一来源里第二句也命中足够多问题词（≥ 最佳句的三分之一）时一并摘录（如“住宿按城市分级，超标部分需事前审批。”）。
        if (next != null && next.overlap() >= Math.max(1, (best.overlap() + 2) / 3)) best = new Pick(n, best.title(), trimEnd(best.sentence()) + "，" + next.sentence(), best.overlap());
        picks.add(best);
      }
    }
    if (picks.isEmpty()) return REFUSAL;
    picks.sort((a, b) -> Integer.compare(b.overlap(), a.overlap()));
    var first = picks.getFirst();
    var sb = new StringBuilder("根据《").append(first.title()).append("》，").append(trimEnd(first.sentence())).append("[").append(first.source()).append("]。");
    if (picks.size() > 1 && picks.get(1).overlap() >= first.overlap() - 1 && picks.get(1).source() != first.source()) {
      var second = picks.get(1);
      sb.append("另外，《").append(second.title()).append("》写明：").append(trimEnd(second.sentence())).append("[").append(second.source()).append("]。");
    }
    return sb.toString();
  }

  private static String trimEnd(String s) {
    return s.replaceAll("[。！？!?；;，,]+$", "");
  }

  private static String after(String text, String marker) {
    int i = text.lastIndexOf(marker);
    return i < 0 ? text.strip() : text.substring(i + marker.length()).strip();
  }
}
