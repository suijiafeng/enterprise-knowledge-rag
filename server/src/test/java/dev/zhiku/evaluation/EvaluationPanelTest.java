package dev.zhiku.evaluation;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 用示例数据集和脚本化模型跑通导入、两次不同参数的评测，以及拒绝不存在的重排/切块开关。 */
class EvaluationPanelTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired IngestionWorker worker;
  @Autowired TestChatModel model;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  UUID kb;

  @BeforeEach
  void seed() throws Exception {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "评测面板-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, operator);
    ingest("年假办法（示例）", Files.readString(Path.of("..", "scripts", "example-eval", "leave.md")));
    ingest("年假窗口说明（示例）", Files.readString(Path.of("..", "scripts", "example-eval", "trap.md")));
    ingest("差旅办法（示例）", Files.readString(Path.of("..", "scripts", "example-eval", "travel.md")));
    model.script = messages -> "司龄满十年的员工每年可以休假十五天[1]。申请须提前三个工作日。";
    model.promptTokens = 12;
    model.completionTokens = 8;
  }

  @AfterEach
  void resetModel() {
    model.reset();
  }

  @Test
  void importsTheExampleSetAndTwoParameterRunsChangeRetrievalAndFaithfulness() throws Exception {
    var dataset = Files.readString(Path.of("..", "scripts", "example-eval", "dataset.json"));
    importJson(dataset, true).andExpect(status().isCreated());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-cases", kb))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM eval_case WHERE kb_id = ? AND status = 'approved' AND source LIKE 'example:%'", Integer.class, kb)).isEqualTo(3);

    var narrow = run("{\"k\":1,\"contextChunks\":1,\"generate\":true,\"judge\":\"simulated\"}");
    var wide = run("{\"k\":5,\"contextChunks\":5,\"generate\":true,\"judge\":\"simulated\"}");

    assertThat(narrow.get("rerankEnabled").asBoolean()).isFalse();
    assertThat(narrow.get("chunkMaxChars").asInt()).isEqualTo(800);
    assertThat(narrow.get("chunkOverlap").asInt()).isEqualTo(100);
    assertThat(narrow.get("judge").asText()).isEqualTo("simulated");
    assertThat(narrow.get("chatModel").asText()).isEqualTo("test-scripted");
    assertThat(narrow.get("generate").asBoolean()).isTrue();
    assertThat(narrow.get("contextChunks").asInt()).isEqualTo(1);
    assertThat(wide.get("k").asInt()).isEqualTo(5);

    var trap = result(narrow, "年假申请窗口年假申请窗口年假申请窗口");
    assertThat(trap.get("hit").asBoolean()).isFalse();
    assertThat(trap.get("retrieved").get(0).get("documentTitle").asText()).isEqualTo("年假窗口说明（示例）");
    assertThat(trap.get("faithfulness").asDouble()).isEqualTo(0d);
    assertThat(trap.get("relevance").asDouble()).isEqualTo(1d);
    assertThat(trap.get("promptTokens").asInt()).isEqualTo(12);
    assertThat(trap.get("completionTokens").asInt()).isEqualTo(8);
    assertThat(trap.get("citation").asText()).isEqualTo("ok");
    assertThat(trap.get("answer").asText()).contains("休假十五天");

    assertThat(result(wide, "年假申请窗口年假申请窗口年假申请窗口").get("hit").asBoolean()).isTrue();
    assertThat(narrow.get("summary").get("hit").get("num").asInt()).isLessThan(wide.get("summary").get("hit").get("num").asInt());
    assertThat(narrow.get("summary").get("mrr").asDouble()).isLessThan(wide.get("summary").get("mrr").asDouble());
    assertThat(narrow.get("summary").get("faithfulness").asDouble()).isLessThan(wide.get("summary").get("faithfulness").asDouble());
    assertThat(narrow.get("summary").get("p50Ms").isNumber()).isTrue();
    assertThat(narrow.get("summary").get("p95Ms").isNumber()).isTrue();
    assertThat(wide.get("summary").get("p50E2eMs").isNumber()).isTrue();
    assertThat(narrow.get("summary").get("judged").asInt()).isEqualTo(3);

    var listed = json.readTree(mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs", kb))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(listed).hasSize(2);
    assertThat(listed.get(0).get("k").asInt()).isEqualTo(5);
    var id = listed.get(1).get("id").asText();
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs/{id}", kb, id))).andExpect(status().isOk());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs/{id}", kb, UUID.randomUUID()))).andExpect(status().isNotFound());

    int before = jdbc.queryForObject("SELECT count(*) FROM eval_case WHERE kb_id = ?", Integer.class, kb);
    importJson(dataset.replace("司龄满十年的员工每年可以休假十五天", "原文里没有这句话"), true).andExpect(status().isUnprocessableEntity());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM eval_case WHERE kb_id = ?", Integer.class, kb)).isEqualTo(before);

    importRaw("text/csv", """
        question,label,expectedAnswer,requiredFacts,documentTitle,versionLabel,quote
        差旅标准是什么,answerable,超标部分需事前审批,超标部分需事前审批,差旅办法（示例）,v1,超标部分需事前审批
        """, false).andExpect(status().isCreated());
    assertThat(jdbc.queryForObject("SELECT status FROM eval_case WHERE question = '差旅标准是什么'", String.class)).isEqualTo("candidate");

    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb)).contentType("application/json").content("{\"k\":5,\"rerank\":true,\"rerankPool\":1}"))
        .andExpect(status().isUnprocessableEntity());
    var reranked = run("{\"k\":1,\"contextChunks\":1,\"generate\":false,\"rerank\":true,\"rerankPool\":5}");
    assertThat(reranked.get("rerankEnabled").asBoolean()).isTrue();
    assertThat(reranked.get("rerankPool").asInt()).isEqualTo(5);
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb)).contentType("application/json").content("{\"k\":1,\"chunkStrategy\":\"hnsw\"}"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb)).contentType("application/json").content("{\"k\":1,\"generate\":true,\"judge\":\"llm\"}"))
        .andExpect(status().isOk());

    var settings = json.readTree(mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-settings", kb))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(settings.get("chunkStrategy").asText()).isEqualTo("fixed-overlap");
    assertThat(settings.get("rerankEnabled").asBoolean()).isTrue();
    assertThat(settings.get("rerank").asText()).contains("BM25");
    assertThat(settings.get("chatConfigured").asBoolean()).isTrue();

    mvc.perform(as(get("/api/knowledge-bases/{kb}/eval-runs", kb), UUID.randomUUID(), Role.KB_OPERATOR)).andExpect(status().isForbidden());
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-cases/import", kb), UUID.randomUUID(), Role.EMPLOYEE)
        .contentType("application/json").content(dataset)).andExpect(status().isForbidden());
  }

  JsonNode run(String body) throws Exception {
    return json.readTree(mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb)).contentType("application/json").content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  JsonNode result(JsonNode run, String question) {
    for (var result : run.get("results")) {
      if (question.equals(result.get("question").asText())) return result;
    }
    throw new AssertionError("missing " + question);
  }

  ResultActions importJson(String body, boolean approve) throws Exception {
    return importRaw("application/json", body, approve);
  }

  ResultActions importRaw(String type, String body, boolean approve) throws Exception {
    return mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-cases/import", kb).param("approve", Boolean.toString(approve)))
        .contentType(type).content(body));
  }

  void ingest(String title, String text) throws Exception {
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", doc, kb, title, operator);
    jdbc.update("""
        INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at)
        VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())
        """, version, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", "doc.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(as(req)).andExpect(status().isAccepted());
    assertThat(worker.processNext()).isTrue();
  }

  <T extends MockHttpServletRequestBuilder> T as(T req) {
    return as(req, operator, Role.KB_OPERATOR);
  }

  <T extends MockHttpServletRequestBuilder> T as(T req, UUID user, Role... roles) {
    req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)));
    return req;
  }
}
