package dev.zhiku.chat;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestJwt;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** PRD Q04：客户端请求 id 只接受一次；重复请求不会再启动生成，且可查询其结果。 */
class ChatRequestTest extends IntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired IngestionWorker worker;
  @Autowired TestChatModel model;

  final UUID enterprise = UUID.randomUUID();
  final UUID operator = UUID.randomUUID();
  final UUID employee = UUID.randomUUID();
  final AtomicInteger calls = new AtomicInteger();
  UUID kb;

  @BeforeEach
  void seed() throws Exception {
    model.reset();
    model.script = m -> {
      calls.incrementAndGet();
      return "年假按司龄计算[1]。";
    };
    jdbc.update("UPDATE ingestion_job SET status = 'succeeded' WHERE status IN ('queued', 'running')");
    kb = UUID.randomUUID();
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "幂等-" + kb);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?), (?, ?)", kb, operator, kb, employee);
    jdbc.update("INSERT INTO document (id, kb_id, title, created_by) VALUES (?, ?, '员工手册', ?)", doc, kb, operator);
    jdbc.update("INSERT INTO content_version (id, document_id, version_label, effective_from, created_by, approved_at, published_at) "
        + "VALUES (?, ?, 'v1', now() - interval '1 day', ?, now(), now())", version, doc, operator);
    var req = multipart("/api/knowledge-bases/{kb}/documents/{doc}/versions/{v}/content", kb, doc, version)
        .file(new MockMultipartFile("file", "handbook.md", "text/markdown", "# 年假\n\n年假按司龄计算。".getBytes(StandardCharsets.UTF_8)));
    req.with(r -> { r.setMethod("PUT"); return r; });
    mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.token(enterprise, operator, Role.KB_OPERATOR)))).andExpect(status().isAccepted());
    worker.processNext();
  }

  @AfterEach
  void reset() {
    model.reset();
  }

  String token(UUID user) {
    return bearer(TestJwt.token(enterprise, user, Role.EMPLOYEE));
  }

  String body(UUID clientRequestId, String question) {
    return "{\"kbId\":\"" + kb + "\",\"question\":\"" + question + "\",\"clientRequestId\":\"" + clientRequestId + "\"}";
  }

  ResultActions post(UUID user, String body) throws Exception {
    return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/chat")
        .header(HttpHeaders.AUTHORIZATION, token(user)).contentType("application/json").content(body));
  }

  String stream(UUID user, String body) throws Exception {
    var started = post(user, body).andExpect(request().asyncStarted()).andReturn();
    started.getAsyncResult(10_000);
    return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  ResultActions lookup(UUID user, UUID clientRequestId) throws Exception {
    return mvc.perform(get("/api/chat/requests/{id}", clientRequestId).header(HttpHeaders.AUTHORIZATION, token(user)));
  }

  @Test
  void repeatingARequestIdNeverStartsAnotherGeneration() throws Exception {
    var id = UUID.randomUUID();
    assertThat(stream(employee, body(id, "年假有几天?"))).contains("event:done");
    post(employee, body(id, "年假有几天?")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_DUPLICATE"));
    post(employee, body(id, "病假怎么请?")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));
    assertThat(calls.get()).isEqualTo(1);

    var messageId = jdbc.queryForObject("SELECT m.id FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ?", UUID.class, kb);
    lookup(employee, id).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("complete"))
        .andExpect(jsonPath("$.messageId").value(messageId.toString()))
        .andExpect(jsonPath("$.conversationId").isString())
        .andExpect(jsonPath("$.answer").value("年假按司龄计算[1]。"));
    var completed = jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM chat_request WHERE client_request_id = ?", Boolean.class, id);
    assertThat(completed).isTrue();
  }

  @Test
  void historyPageBreaksTiesOnIdWhenTimestampsMatch() throws Exception {
    var first = UUID.randomUUID();
    assertThat(stream(employee, body(first, "年假有几天?"))).contains("event:done");
    var conversation = jdbc.queryForObject("SELECT conversation_id FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ?", UUID.class, kb);
    var second = UUID.randomUUID();
    assertThat(stream(employee, "{\"kbId\":\"" + kb + "\",\"question\":\"那试用期呢?\",\"clientRequestId\":\"" + second
        + "\",\"conversationId\":\"" + conversation + "\"}")).contains("event:done");
    jdbc.update("UPDATE message SET created_at = '2026-01-01T00:00:00Z' WHERE conversation_id = ?", conversation);
    // 全量套件里本线程可能还留着未提交事务；提交后，另一次请求连接才能看到相同的时间。
    jdbc.execute((java.sql.Connection connection) -> {
      if (!connection.getAutoCommit()) connection.commit();
      return null;
    });
    var ordered = jdbc.queryForList("SELECT id::text FROM message WHERE conversation_id = ? ORDER BY id", String.class, conversation);
    assertThat(ordered).hasSize(2);

    var latestBody = mvc.perform(get("/api/conversations/{id}/messages/page", conversation).param("limit", "1")
            .header(HttpHeaders.AUTHORIZATION, token(employee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").isString())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var latestJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(latestBody);
    var laterId = java.util.UUID.fromString(latestJson.at("/items/0/id").asText());
    var laterAt = java.time.Instant.parse(latestJson.at("/items/0/createdAt").asText());
    var earlierBody = mvc.perform(get("/api/conversations/{id}/messages/page", conversation).param("limit", "1")
            .param("before", latestJson.get("nextCursor").asText())
            .header(HttpHeaders.AUTHORIZATION, token(employee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    var earlierJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(earlierBody);
    var earlierId = java.util.UUID.fromString(earlierJson.at("/items/0/id").asText());
    var earlierAt = java.time.Instant.parse(earlierJson.at("/items/0/createdAt").asText());
    assertThat(java.util.List.of(earlierId.toString(), laterId.toString())).containsExactlyInAnyOrderElementsOf(ordered);
    assertThat(ChatCursor.compare(earlierAt, earlierId, laterAt, laterId)).isNegative();
    assertThat(earlierAt.toEpochMilli()).isEqualTo(laterAt.toEpochMilli());
    mvc.perform(get("/api/conversations/{id}/messages/page", conversation).param("before", "%%%")
            .header(HttpHeaders.AUTHORIZATION, token(employee)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void withdrawnSourceIsHiddenFromHistoryAndFromTheReceipt() throws Exception {
    var id = UUID.randomUUID();
    assertThat(stream(employee, body(id, "年假有几天?"))).contains("event:done");
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE document_id IN (SELECT id FROM document WHERE kb_id = ?)", kb);
    var conversation = jdbc.queryForObject("SELECT conversation_id FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.kb_id = ?", UUID.class, kb);
    mvc.perform(get("/api/conversations/{id}/messages", conversation).header(HttpHeaders.AUTHORIZATION, token(employee)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].hidden").value(true))
        .andExpect(jsonPath("$[0].reason").value("SOURCE_UNAVAILABLE"))
        .andExpect(jsonPath("$[0].answer").value(org.hamcrest.Matchers.nullValue()));
    lookup(employee, id).andExpect(status().isOk())
        .andExpect(jsonPath("$.hidden").value(true))
        .andExpect(jsonPath("$.answer").doesNotExist());
  }

  @Test
  void theOutcomeOfAFailedOrRefusedTurnCanBeLookedUp() throws Exception {
    var failed = UUID.randomUUID();
    model.failMidway = true;
    stream(employee, body(failed, "年假有几天?"));
    lookup(employee, failed).andExpect(jsonPath("$.status").value("failed")).andExpect(jsonPath("$.messageId").isString());

    model.reset();
    jdbc.update("UPDATE content_version SET withdrawn_at = now() WHERE document_id IN (SELECT id FROM document WHERE kb_id = ?)", kb);
    var refused = UUID.randomUUID();
    stream(employee, body(refused, "年假有几天?"));
    lookup(employee, refused).andExpect(jsonPath("$.status").value("refused"));
  }

  @Test
  void acceptedRequestsWithoutAnOutcomeAreRunningUntilTheStreamWouldHaveTimedOut() throws Exception {
    var running = UUID.randomUUID();
    var lost = UUID.randomUUID();
    jdbc.update("INSERT INTO chat_request (enterprise_id, user_id, client_request_id, kb_id, digest) VALUES (?, ?, ?, ?, repeat('a', 64))",
        enterprise, employee, running, kb);
    jdbc.update("INSERT INTO chat_request (enterprise_id, user_id, client_request_id, kb_id, digest, accepted_at) "
        + "VALUES (?, ?, ?, ?, repeat('a', 64), now() - interval '1 hour')", enterprise, employee, lost, kb);
    lookup(employee, running).andExpect(jsonPath("$.status").value("running")).andExpect(jsonPath("$.messageId").doesNotExist());
    lookup(employee, lost).andExpect(jsonPath("$.status").value("interrupted"));
  }

  @Test
  void requestIdsBelongToOneUserAndStillRequireAccess() throws Exception {
    var id = UUID.randomUUID();
    stream(employee, body(id, "年假有几天?"));
    lookup(operator, id).andExpect(status().isNotFound());
    lookup(UUID.randomUUID(), id).andExpect(status().isNotFound());
    assertThat(stream(operator, body(id, "年假有几天?"))).contains("event:done");

    jdbc.update("DELETE FROM kb_grant WHERE kb_id = ? AND user_id = ?", kb, employee);
    lookup(employee, id).andExpect(status().isForbidden());
  }

  @Test
  void requestsRejectedBeforeStreamingDoNotConsumeTheId() throws Exception {
    var id = UUID.randomUUID();
    post(employee, "{\"kbId\":\"" + UUID.randomUUID() + "\",\"question\":\"年假?\",\"clientRequestId\":\"" + id + "\"}")
        .andExpect(status().isForbidden());
    assertThat(stream(employee, body(id, "年假有几天?"))).contains("event:done");
  }
}
