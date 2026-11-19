package dev.zhiku.ingestion;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ChunkEditTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired IngestionWorker worker;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  UUID kb;
  UUID documentId;
  UUID versionId;

  static final String SOURCE = "报销需要三张发票。年假另有规定。";

  @BeforeEach
  void seed() throws Exception {
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    documentId = UUID.randomUUID();
    versionId = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "切块-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, operator);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, ?, ?)", documentId, kb, "报销办法", operator);
    jdbc.update("""
        INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at)
        VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())
        """, versionId, documentId, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, documentId, versionId)
        .file(new MockMultipartFile("file", "doc.md", "text/markdown", SOURCE.getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(as(req)).andExpect(status().isAccepted());
    assertThat(worker.processNext()).isTrue();
  }

  @Test
  void editSplitAndDeleteThenTheNewTextIsWhatGetsEmbedded() throws Exception {
    mvc.perform(as(get("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/chunks", kb, documentId, versionId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].text").value(SOURCE));
    var body = """
        {"chunks":[
          {"text":"报销需要三张发票","startOffset":0,"endOffset":9},
          {"text":"只在编辑后的切块里出现的口令紫藤","startOffset":9,"endOffset":16}
        ]}
        """;
    mvc.perform(as(put("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/chunks", kb, documentId, versionId))
        .contentType("application/json").content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_embedding e JOIN chunk c ON c.id = e.chunk_id WHERE c.version_id = ?", Integer.class, versionId)).isEqualTo(2);
    var kept = """
        {"chunks":[{"text":"只在编辑后的切块里出现的口令紫藤","startOffset":9,"endOffset":16}]}
        """;
    mvc.perform(as(put("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/chunks", kb, documentId, versionId))
        .contentType("application/json").content(kept)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    var found = mvc.perform(as(post("/api/knowledge-bases/{kb}/retrieval-debug", kb)).contentType("application/json")
        .content("{\"query\":\"口令紫藤\",\"k\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(json.readTree(found).get("candidates").get(0).get("text").asText()).contains("口令紫藤");
    mvc.perform(as(put("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/chunks", kb, documentId, versionId))
        .contentType("application/json").content("{\"chunks\":[{\"text\":\"越界\",\"startOffset\":0,\"endOffset\":999}]}")).andExpect(status().isUnprocessableEntity());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/chunks", kb, documentId, versionId), UUID.randomUUID(), Role.EMPLOYEE))
        .andExpect(status().isForbidden());
  }

  <T extends MockHttpServletRequestBuilder> T as(T req) {
    return as(req, operator, Role.KB_OPERATOR);
  }

  <T extends MockHttpServletRequestBuilder> T as(T req, UUID user, Role... roles) {
    req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, user, roles)));
    return req;
  }
}
