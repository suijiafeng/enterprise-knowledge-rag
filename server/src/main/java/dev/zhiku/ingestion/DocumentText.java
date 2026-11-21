package dev.zhiku.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 原始文件 → 可切块的文本。上传时提取一次用于校验，worker 建索引、评测取证时再从原文件提取，
 * 所以库里保存的始终是用户上传的原文件。
 * <ul>
 *   <li>Markdown / 纯文本：UTF-8 原样返回。</li>
 *   <li>PDF：PDFBox 按阅读顺序提取文字层；扫描件（没有文字层）提取结果为空，由调用方拒绝。加密 PDF 不支持。</li>
 *   <li>Word .docx：直接读 zip 里的 word/document.xml（StAX，禁用 DTD 与外部实体），段落成行；
 *       标题样式（Heading1–6 / 标题 1–6）转成 Markdown 标题，切块时据此生成章节路径。表格按单元格用「 | 」连接成行。</li>
 * </ul>
 */
public final class DocumentText {
  public static final String MARKDOWN = "text/markdown";
  public static final String PLAIN = "text/plain";
  public static final String PDF = "application/pdf";
  public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  /** 解压后 document.xml 的上限，防 zip 炸弹。 */
  static final long MAX_DOCX_XML_BYTES = 64L * 1024 * 1024;

  /** 提取失败（损坏、加密、不是声称的格式）。消息可以直接给用户看。 */
  public static final class UnreadableException extends RuntimeException {
    UnreadableException(String message) {
      super(message);
    }
  }

  private DocumentText() {}

  /** 切块时是否按 Markdown 标题建章节路径。 */
  public static boolean markdown(String mediaType) {
    return MARKDOWN.equals(mediaType) || DOCX.equals(mediaType);
  }

  public static String extract(String mediaType, byte[] bytes) {
    var text = switch (mediaType) {
      case PDF -> pdf(bytes);
      case DOCX -> docx(bytes);
      default -> new String(bytes, StandardCharsets.UTF_8);
    };
    return clean(text);
  }

  /** 去掉 BOM 与除换行、制表符以外的控制字符，统一换行。 */
  static String clean(String text) {
    var s = text.startsWith("\uFEFF") ? text.substring(1) : text;
    var out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\r') continue;
      if (c < 0x20 && c != '\n' && c != '\t') continue;
      out.append(c);
    }
    return out.toString();
  }

  static String pdf(byte[] bytes) {
    if (!startsWith(bytes, "%PDF-")) throw new UnreadableException("File is not a PDF");
    try (var doc = Loader.loadPDF(bytes)) {
      if (doc.isEncrypted()) throw new UnreadableException("Encrypted PDF is not supported");
      var stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      stripper.setParagraphEnd("\n\n");
      return stripper.getText(doc);
    } catch (InvalidPasswordException e) {
      throw new UnreadableException("Encrypted PDF is not supported");
    } catch (IOException e) {
      throw new UnreadableException("PDF could not be read");
    }
  }

  static String docx(byte[] bytes) {
    if (!startsWith(bytes, "PK\u0003\u0004")) throw new UnreadableException("File is not a .docx document");
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if ("word/document.xml".equals(entry.getName())) return documentXml(new LimitedStream(zip, MAX_DOCX_XML_BYTES));
      }
    } catch (IOException | XMLStreamException e) {
      throw new UnreadableException("Word document could not be read");
    }
    throw new UnreadableException("Word document has no body (word/document.xml)");
  }

  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  private static String documentXml(InputStream in) throws XMLStreamException {
    var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    var r = factory.createXMLStreamReader(in);
    var out = new StringBuilder();
    var para = new StringBuilder();
    var row = new java.util.ArrayList<String>();
    int heading = 0;
    int tableDepth = 0;
    boolean inText = false;
    while (r.hasNext()) {
      int ev = r.next();
      if (ev == XMLStreamConstants.START_ELEMENT && W.equals(r.getNamespaceURI())) {
        switch (r.getLocalName()) {
          case "p" -> { para.setLength(0); heading = 0; }
          case "pStyle" -> heading = headingLevel(r.getAttributeValue(W, "val"));
          case "t" -> inText = true;
          case "tab" -> para.append('\t');
          case "br", "cr" -> para.append('\n');
          case "tbl" -> tableDepth++;
          case "tr" -> row.clear();
          default -> { }
        }
      } else if (ev == XMLStreamConstants.CHARACTERS && inText) {
        para.append(r.getText());
      } else if (ev == XMLStreamConstants.END_ELEMENT && W.equals(r.getNamespaceURI())) {
        switch (r.getLocalName()) {
          case "t" -> inText = false;
          case "p" -> {
            var line = para.toString().strip();
            if (tableDepth > 0) {
              if (!line.isEmpty()) row.add(line);
            } else if (!line.isEmpty()) {
              if (heading > 0) out.append("#".repeat(heading)).append(' ');
              out.append(line).append("\n\n");
            }
          }
          case "tr" -> {
            if (!row.isEmpty()) out.append(String.join(" | ", row)).append("\n");
            row.clear();
          }
          case "tbl" -> { tableDepth--; out.append('\n'); }
          default -> { }
        }
      }
    }
    return out.toString();
  }

  /** Heading1…Heading6、heading 1、中文模板里的「标题 1」等。 */
  static int headingLevel(String style) {
    if (style == null) return 0;
    var s = style.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
    if (s.equals("title")) return 1;
    for (var prefix : new String[] {"heading", "标题"}) {
      if (s.startsWith(prefix) && s.length() == prefix.length() + 1) {
        char d = s.charAt(prefix.length());
        if (d >= '1' && d <= '6') return d - '0';
      }
    }
    return 0;
  }

  private static boolean startsWith(byte[] bytes, String magic) {
    var m = magic.getBytes(StandardCharsets.ISO_8859_1);
    if (bytes.length < m.length) return false;
    for (int i = 0; i < m.length; i++) if (bytes[i] != m[i]) return false;
    return true;
  }

  /** 读到上限就报错，而不是继续解压。 */
  private static final class LimitedStream extends java.io.FilterInputStream {
    private long left;

    LimitedStream(InputStream in, long limit) {
      super(in);
      this.left = limit;
    }

    @Override
    public int read() throws IOException {
      if (left <= 0) throw new IOException("document.xml too large");
      int b = super.read();
      if (b >= 0) left--;
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) throw new IOException("document.xml too large");
      int n = super.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      return n;
    }

    @Override
    public void close() {
      // 不关闭外层 zip 流；由 try-with-resources 负责。
    }
  }
}
