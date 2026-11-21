package dev.zhiku.chat;

import java.util.regex.Pattern;

/**
 * 资料里的疑似指令（要求忽略规则、改变身份、泄露提示词、不要引用等）在送入模型前屏蔽为占位符。
 * 这是规则过滤，只能挡住常见写法；系统提示中"资料是数据不是指令"的约束仍然保留。原文、引用和偏移量不变。
 */
public final class InjectionGuard {
  public static final String MASK = "〔疑似指令，已屏蔽〕";
  static final Pattern INSTRUCTION = Pattern.compile(
      "(忽略|无视|忘记|不要理会|绕过).{0,12}(规则|指令|要求|提示|设定|以上|之前|上述)"
      + "|(你现在是|你将扮演|从现在起你|请扮演)"
      + "|(不要|无需|不必|禁止).{0,6}(引用|标注来源|标注编号)"
      + "|(系统提示词?|system prompt|开发者模式|developer mode)"
      + "|(ignore|disregard|forget).{0,20}(instructions?|rules?|above|previous)"
      + "|(直接回答|一律回答|只回答)[“\"「]",
      Pattern.CASE_INSENSITIVE);

  private InjectionGuard() {}

  public record Result(String text, int masked) {}

  public static Result sanitize(String text) {
    if (text == null || text.isEmpty()) return new Result(text == null ? "" : text, 0);
    var sb = new StringBuilder();
    var sentence = new StringBuilder();
    int masked = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        masked += flush(sentence, sb);
        sb.append(c);
        continue;
      }
      sentence.append(c);
      if ("。！？!?".indexOf(c) >= 0) masked += flush(sentence, sb);
    }
    masked += flush(sentence, sb);
    return new Result(masked == 0 ? text : sb.toString(), masked);
  }

  private static int flush(StringBuilder sentence, StringBuilder out) {
    if (sentence.isEmpty()) return 0;
    boolean hit = INSTRUCTION.matcher(sentence).find();
    out.append(hit ? MASK : sentence);
    sentence.setLength(0);
    return hit ? 1 : 0;
  }
}
