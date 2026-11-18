package dev.zhiku.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EvalImportTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void readsJsonCasesAndRejectsChunkIds() {
    var batch = EvalImport.parse("""
        {"name":"示例-员工制度","example":true,"cases":[
          {"question":"年假几天?","label":"answerable","expectedAnswer":"十五天","requiredFacts":["十五天"],
           "evidence":[{"documentTitle":"年假办法（示例）","versionLabel":"v1","quote":"十五天"}]}
        ]}
        """, "application/json", json);
    assertThat(batch.example()).isTrue();
    assertThat(batch.name()).isEqualTo("示例-员工制度");
    assertThat(batch.cases()).hasSize(1);
    assertThat(batch.cases().getFirst().evidence().getFirst().quote()).isEqualTo("十五天");
    assertThatThrownBy(() -> EvalImport.parse("""
        {"name":"x","cases":[{"question":"q","label":"answerable","evidence":[{"chunkId":"c","documentTitle":"d","versionLabel":"v1","quote":"十五天"}]}]}
        """, "application/json", json)).isInstanceOf(EvalImport.Invalid.class).hasMessageContaining("chunk id");
  }

  @Test
  void readsCsvIncludingQuotesAndPipes() {
    var batch = EvalImport.parse("""
        question,label,expectedAnswer,requiredFacts,documentTitle,versionLabel,quote,appliesTo
        "年假,几天?",answerable,十五天,十五天|提前三个工作日,年假办法（示例）,v1,"可以休假十五天",正式员工
        """, "text/csv", json);
    var draft = batch.cases().getFirst();
    assertThat(draft.question()).isEqualTo("年假,几天?");
    assertThat(draft.facts()).containsExactly("十五天", "提前三个工作日");
    assertThat(draft.appliesTo()).isEqualTo("正式员工");
    assertThat(batch.example()).isFalse();
  }

  @Test
  void rejectsEmptyFilesBadHeadersAndTooShortQuotes() {
    assertThatThrownBy(() -> EvalImport.parse("  ", "text/csv", json)).isInstanceOf(EvalImport.Invalid.class);
    assertThatThrownBy(() -> EvalImport.parse("question,quote\n", "text/csv", json)).isInstanceOf(EvalImport.Invalid.class);
    assertThatThrownBy(() -> EvalImport.parse("""
        question,label,expectedAnswer,requiredFacts,documentTitle,versionLabel,quote
        q,answerable,a,f,doc,v1,x
        """, "text/csv", json)).isInstanceOf(EvalImport.Invalid.class);
    assertThatThrownBy(() -> EvalImport.parse("{\"name\":\"x\"}", "application/json", json)).isInstanceOf(EvalImport.Invalid.class);
    assertThatThrownBy(() -> EvalImport.parse("not a dataset", "text/plain", json)).isInstanceOf(EvalImport.Invalid.class);
  }
}
