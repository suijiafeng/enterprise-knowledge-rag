package dev.zhiku.document;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.auth.Role;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 打开 zhiku.document.require-approval 后，发布必须在批准之后。 */
@TestPropertySource(properties = "zhiku.document.require-approval=true")
class DocumentApprovalRequiredTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req) {
    return req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)));
  }

  UUID create(String path, String body) throws Exception {
    var response = mvc.perform(as(post(path)).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
    return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
  }

  @Test
  void publishIsRejectedUntilTheVersionIsApproved() throws Exception {
    var kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "制度-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, operator);
    var doc = create("/api/knowledge-bases/" + kb + "/documents", "{\"title\":\"员工手册\"}");
    var version = create("/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions", "{\"versionLabel\":\"v1\",\"effectiveFrom\":\"2026-01-01\"}");
    var base = "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + version;

    mvc.perform(as(post(base + "/publish"))).andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("Version must be approved before publishing"));
    assertThat(jdbc.queryForObject("SELECT approved_at IS NULL FROM content_version WHERE id = ?", Boolean.class, version)).isTrue();

    mvc.perform(as(post(base + "/approve"))).andExpect(status().isNoContent());
    mvc.perform(as(post(base + "/publish"))).andExpect(status().isNoContent());
    mvc.perform(get("/api/status")).andExpect(jsonPath("$.requireApproval").value(true));
  }
}
