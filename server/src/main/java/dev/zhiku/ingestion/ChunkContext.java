package dev.zhiku.ingestion;

/** chunk 的上下文前缀：文档标题 › 标题路径。正文和偏移量不变，评测原文区间照常映射。 */
public final class ChunkContext {
  private ChunkContext() {}

  public static String of(String title, String path) {
    var t = title == null ? "" : title.strip();
    if (path == null || path.isBlank()) return t;
    return t.isEmpty() ? path.strip() : t + " › " + path.strip();
  }

  /** 送入 embedding 的文本：上下文一行 + 正文。 */
  public static String embedText(String title, String path, String text) {
    var context = of(title, path);
    return context.isEmpty() ? text : context + "\n" + text;
  }
}
