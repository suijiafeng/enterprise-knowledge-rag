package dev.zhiku.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 带重叠的定长字符切块，优先在段落、行和句子边界处切分。Markdown 按标题分节：
 * 一个 chunk 不会跨越两个小节（只有标题、没有正文的小节并入下一节），并记录完整标题路径。
 * 偏移量基于 {@link #normalize(String)} 的输出，以便评测标注能将来源片段映射到 chunk。
 */
public final class TextChunker {
  /** heading 为最近的标题；path 为从一级到最近标题的完整路径（以 " › " 连接），无标题时为 null。 */
  public record Chunk(int seq, int start, int end, String heading, String text, String path) {
    public Chunk(int seq, int start, int end, String heading, String text) {
      this(seq, start, end, heading, text, heading);
    }
  }

  private record Heading(int level, String title, String path) {}

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
  private static final String SENTENCE_ENDS = "。！？；.!?;";

  private final int maxChars;
  private final int overlap;
  private final int maxChunks;

  public TextChunker(int maxChars, int overlap, int maxChunks) {
    if (maxChars <= 0 || overlap < 0 || overlap >= maxChars || maxChunks <= 0) throw new IllegalArgumentException("Invalid chunk settings");
    this.maxChars = maxChars;
    this.overlap = overlap;
    this.maxChunks = maxChunks;
  }

  public static String normalize(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  public List<Chunk> chunk(String raw, boolean markdown) {
    var text = normalize(raw);
    var headings = markdown ? headings(text) : new TreeMap<Integer, Heading>();
    var chunks = new ArrayList<Chunk>();
    var bounds = sections(text, headings);
    for (int s = 0; s < bounds.size() - 1; s++) chunkRange(text, bounds.get(s), bounds.get(s + 1), headings, chunks);
    return chunks;
  }

  /** 小节边界：0、每个"其前一节有正文"的标题行起点、文本末尾。 */
  private static List<Integer> sections(String text, TreeMap<Integer, Heading> headings) {
    var bounds = new ArrayList<Integer>();
    bounds.add(0);
    for (int start : headings.keySet()) {
      if (start == 0) continue;
      int prev = bounds.getLast();
      if (hasBody(text, prev, start, headings)) bounds.add(start);
    }
    bounds.add(text.length());
    return bounds;
  }

  /** [from, to) 中除标题行外是否还有非空白内容。 */
  private static boolean hasBody(String text, int from, int to, TreeMap<Integer, Heading> headings) {
    int i = from;
    while (i < to) {
      int lineEnd = text.indexOf('\n', i);
      if (lineEnd < 0 || lineEnd > to) lineEnd = to;
      if (!headings.containsKey(i) && !text.substring(i, lineEnd).isBlank()) return true;
      i = lineEnd + 1;
    }
    return false;
  }

  private void chunkRange(String text, int from, int to, TreeMap<Integer, Heading> headings, List<Chunk> chunks) {
    int start = skipWhitespace(text, from, to);
    while (start < to) {
      int hardEnd = Math.min(start + maxChars, to);
      int end = hardEnd < to ? breakPoint(text, start, hardEnd) : hardEnd;
      int trimmed = end;
      while (trimmed > start && Character.isWhitespace(text.charAt(trimmed - 1))) trimmed--;
      if (trimmed > start) {
        if (chunks.size() >= maxChunks) throw new IllegalArgumentException("Document exceeds the chunk limit");
        // 以 chunk 中第一行正文定位所属标题：开头连续的标题行（"# 章" 紧接 "## 节"）归最深的那一级。
        var heading = headings.floorEntry(anchor(text, start, trimmed, headings));
        chunks.add(new Chunk(chunks.size(), start, trimmed, heading == null ? null : heading.getValue().title(), text.substring(start, trimmed),
            heading == null ? null : heading.getValue().path()));
      }
      if (end >= to) break;
      int next = skipWhitespace(text, Math.max(end - overlap, start + 1), to);
      if (next < to && Character.isLowSurrogate(text.charAt(next))) next++;
      start = next > start ? next : end;
    }
  }

  private int breakPoint(String text, int start, int hardEnd) {
    int floor = start + maxChars / 2;
    int paragraph = text.lastIndexOf("\n\n", hardEnd - 1);
    if (paragraph >= floor) return paragraph;
    int line = text.lastIndexOf('\n', hardEnd - 1);
    if (line >= floor) return line;
    for (int i = hardEnd - 1; i >= floor; i--) {
      if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) return i + 1;
    }
    return Character.isHighSurrogate(text.charAt(hardEnd - 1)) ? hardEnd - 1 : hardEnd;
  }

  private static int anchor(String text, int start, int end, TreeMap<Integer, Heading> headings) {
    int i = start;
    while (i < end && headings.containsKey(i)) {
      int next = text.indexOf('\n', i);
      if (next < 0 || next >= end) return i;
      i = skipWhitespace(text, next + 1, end);
    }
    return i < end ? i : start;
  }

  private static int skipWhitespace(String text, int from, int to) {
    int i = from;
    while (i < to && Character.isWhitespace(text.charAt(i))) i++;
    return i;
  }

  private static TreeMap<Integer, Heading> headings(String text) {
    var map = new TreeMap<Integer, Heading>();
    var stack = new java.util.ArrayDeque<Heading>();
    int lineStart = 0;
    for (var line : text.split("\n", -1)) {
      var m = HEADING.matcher(line);
      if (m.matches()) {
        int level = m.group(1).length();
        while (!stack.isEmpty() && stack.peek().level() >= level) stack.pop();
        var parent = stack.peek();
        var heading = new Heading(level, m.group(2), parent == null ? m.group(2) : parent.path() + " › " + m.group(2));
        stack.push(heading);
        map.put(lineStart, heading);
      }
      lineStart += line.length() + 1;
    }
    return map;
  }
}
