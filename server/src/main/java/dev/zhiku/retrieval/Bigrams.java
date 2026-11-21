package dev.zhiku.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 字符二元组：中文没有分词器时最朴素、可复现的词法单元。归一化规则与迁移 V27 中
 * 数据库函数 zhiku_bigrams 一致（小写，去掉空白与常见中英文标点），两端才能互相命中。
 */
public final class Bigrams {
  /** 与 V27__hybrid_retrieval.sql 中的正则保持一致。 */
  static final Pattern STRIP = Pattern.compile("[\\s!-/:-@\\[-`{-~，。！？；：、（）《》“”‘’【】—…·「」『』〈〉・－～　]");

  private Bigrams() {}

  public static String normalize(String text) {
    return text == null ? "" : STRIP.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  /** 按出现顺序的全部二元组（含重复），供 BM25 计词频。 */
  public static List<String> all(String text) {
    var points = normalize(text).codePoints().toArray();
    if (points.length < 2) return List.of();
    var out = new ArrayList<String>(points.length - 1);
    for (int i = 0; i < points.length - 1; i++) out.add(new String(points, i, 2));
    return out;
  }

  public static Set<String> distinct(String text) {
    return new LinkedHashSet<>(all(text));
  }

  /** query 的去重二元组中有多少比例出现在 text 里；query 没有二元组时为 0。 */
  public static double coverage(Set<String> query, Set<String> text) {
    if (query.isEmpty()) return 0;
    int hit = 0;
    for (var term : query) if (text.contains(term)) hit++;
    return (double) hit / query.size();
  }

  /** tsquery 字面量（'a' | 'b'），二元组只含字母和数字，单引号已被归一化去掉。 */
  public static String tsquery(Set<String> terms) {
    var sb = new StringBuilder();
    for (var term : terms) {
      if (sb.length() > 0) sb.append(" | ");
      sb.append('\'').append(term.replace("'", "")).append('\'');
    }
    return sb.toString();
  }
}
