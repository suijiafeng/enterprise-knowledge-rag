package dev.zhiku.chat;

import dev.zhiku.retrieval.Bigrams;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 轻量引用支撑检查：回答按句切分，带 [n] 的句子去掉标记后，看它的字符二元组有多少出现在所引来源里。
 * 覆盖率低于 {@link #MIN_SUPPORT} 的句子记为"支撑弱"。这是词面检查，不是语义验证：改写得很远的正确结论
 * 也可能被标为弱，原文照抄的错误组合也可能通过。只提示，不拦截。
 */
public final class CitationSupport {
  public static final double MIN_SUPPORT = 0.3;
  private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,9})\\]");

  private CitationSupport() {}

  /** checked：带引用的句数；supported：支撑达标的句数；weak：支撑弱的句子序号（从 1 开始，按带引用的句子计）。 */
  public record Result(int checked, int supported, List<Integer> weak) {
    public boolean allSupported() {
      return checked == supported;
    }
  }

  /** answer 中 [n] 指向 sources 的第 n 条（从 1 开始）。 */
  public static Result check(String answer, List<String> sources) {
    var sentences = sentences(answer == null ? "" : answer);
    int checked = 0;
    int supported = 0;
    var weak = new ArrayList<Integer>();
    for (var sentence : sentences) {
      var cited = cited(sentence);
      if (cited.isEmpty()) continue;
      checked++;
      var claim = Bigrams.distinct(MARKER.matcher(sentence).replaceAll(""));
      var evidence = new HashSet<String>();
      for (int n : cited) if (n >= 1 && n <= sources.size()) evidence.addAll(Bigrams.distinct(sources.get(n - 1)));
      if (claim.isEmpty() || Bigrams.coverage(claim, evidence) >= MIN_SUPPORT) supported++;
      else weak.add(checked);
    }
    return new Result(checked, supported, List.copyOf(weak));
  }

  /** 回答中出现过的来源编号。 */
  public static Set<Integer> cited(String text) {
    var out = new TreeSet<Integer>();
    if (text == null) return out;
    var m = MARKER.matcher(text);
    while (m.find()) out.add(Integer.parseInt(m.group(1)));
    return out;
  }

  /** 按句末标点和换行切句；紧跟在句末之后的 [n] 归入前一句（"……十五天。[1]"）。 */
  static List<String> sentences(String text) {
    var out = new ArrayList<String>();
    var current = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      current.append(c);
      i++;
      if ("。！？!?；;\n".indexOf(c) >= 0) {
        var m = MARKER.matcher(text).region(i, text.length());
        while (m.lookingAt()) {
          current.append(m.group());
          i = m.end();
          m = MARKER.matcher(text).region(i, text.length());
        }
        if (!current.toString().isBlank()) out.add(current.toString());
        current.setLength(0);
      }
    }
    if (!current.toString().isBlank()) out.add(current.toString());
    return out;
  }
}
