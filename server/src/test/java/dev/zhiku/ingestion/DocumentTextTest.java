package dev.zhiku.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.zhiku.support.TestDocuments;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentTextTest {
  @Test
  void extractsTheTextLayerOfAPdf() {
    var text = DocumentText.extract(DocumentText.PDF, TestDocuments.pdf("Annual leave policy", "Employees with 10 years of service get 15 days."));
    assertThat(text).contains("Annual leave policy").contains("15 days");
  }

  @Test
  void aPdfWithoutTextExtractsToBlankAndBrokenFilesAreUnreadable() {
    assertThat(DocumentText.extract(DocumentText.PDF, TestDocuments.pdf())).isBlank();
    assertThatThrownBy(() -> DocumentText.extract(DocumentText.PDF, "%PDF-1.7 truncated".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(DocumentText.UnreadableException.class);
    assertThatThrownBy(() -> DocumentText.extract(DocumentText.PDF, "not a pdf".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(DocumentText.UnreadableException.class);
  }

  @Test
  void turnsWordHeadingsIntoMarkdownAndKeepsTables() {
    var text = DocumentText.extract(DocumentText.DOCX,
        TestDocuments.docx("# 差旅费管理办法（示例）", "## 第七条 住宿标准", "一线城市每晚不超过 600 元。", "城市 | 上限", "其他 | 450 元"));
    assertThat(text).contains("# 差旅费管理办法（示例）\n").contains("## 第七条 住宿标准\n").contains("一线城市每晚不超过 600 元。")
        .contains("城市 | 上限").contains("其他 | 450 元");
    assertThat(DocumentText.markdown(DocumentText.DOCX)).isTrue();
    assertThat(DocumentText.markdown(DocumentText.PDF)).isFalse();
  }

  @Test
  void recognisesEnglishAndChineseHeadingStyles() {
    assertThat(DocumentText.headingLevel("Heading2")).isEqualTo(2);
    assertThat(DocumentText.headingLevel("heading 3")).isEqualTo(3);
    assertThat(DocumentText.headingLevel("标题 1")).isEqualTo(1);
    assertThat(DocumentText.headingLevel("Title")).isEqualTo(1);
    assertThat(DocumentText.headingLevel("Normal")).isZero();
    assertThat(DocumentText.headingLevel("Heading9")).isZero();
  }

  @Test
  void refusesZipsThatAreNotWordDocumentsAndExternalEntities() {
    assertThatThrownBy(() -> DocumentText.extract(DocumentText.DOCX, "PK\u0003\u0004junk".getBytes(StandardCharsets.ISO_8859_1)))
        .isInstanceOf(DocumentText.UnreadableException.class);
    var xxe = TestDocuments.zipped("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>a&e;b</w:t></w:r></w:p></w:body></w:document>");
    String text;
    try {
      text = DocumentText.extract(DocumentText.DOCX, xxe);
    } catch (DocumentText.UnreadableException e) {
      text = "";
    }
    assertThat(text).doesNotContain("root:");
  }

  @Test
  void stripsControlCharactersAndCarriageReturns() {
    assertThat(DocumentText.clean("\uFEFFa\r\nb\u0007c\td")).isEqualTo("a\nbc\td");
  }
}
