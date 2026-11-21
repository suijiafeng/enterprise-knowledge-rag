package dev.zhiku.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** 测试用的最小 PDF / Word 文件（内容虚构）。 */
public final class TestDocuments {
  private TestDocuments() {}

  /** 每行一段文字；PDF 标准字体不含中文，所以这里用英文。 */
  public static byte[] pdf(String... lines) {
    try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      if (lines.length > 0) {
        try (var cs = new PDPageContentStream(doc, page)) {
          cs.beginText();
          cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          cs.setLeading(18);
          cs.newLineAtOffset(72, 720);
          for (var line : lines) {
            cs.showText(line);
            cs.newLine();
          }
          cs.endText();
        }
      }
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 段落：以 "#" 开头的按标题样式（# = Heading1，## = Heading2）；"|" 分隔的行写成一行表格。 */
  public static byte[] docx(String... paragraphs) {
    var body = new StringBuilder();
    for (var p : paragraphs) {
      if (p.contains("|")) {
        body.append("<w:tbl><w:tr>");
        for (var cell : p.split("\\|")) body.append("<w:tc>").append(para(cell.strip(), 0)).append("</w:tc>");
        body.append("</w:tr></w:tbl>");
        continue;
      }
      int level = 0;
      while (level < p.length() && p.charAt(level) == '#') level++;
      body.append(para(p.substring(level).strip(), level));
    }
    return zipped("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>");
  }

  /** 把任意 XML 作为 word/document.xml 打包。 */
  public static byte[] zipped(String xml) {
    try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(xml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.finish();
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String para(String text, int heading) {
    var style = heading > 0 ? "<w:pPr><w:pStyle w:val=\"Heading" + heading + "\"/></w:pPr>" : "";
    var escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return "<w:p>" + style + "<w:r><w:t xml:space=\"preserve\">" + escaped + "</w:t></w:r></w:p>";
  }
}
