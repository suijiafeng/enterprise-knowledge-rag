package dev.zhiku.auth;

import static dev.zhiku.support.TestJwt.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.platform.RateLimiter;
import dev.zhiku.support.IntegrationTest;
import dev.zhiku.support.TestJwt;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 生产口径：只接受绑定会话的令牌，开启限流（上限调小以便测试）。 */
@TestPropertySource(properties = {"zhiku.auth.dev-tokens=false", "zhiku.rate-limit.enabled=true",
    "zhiku.rate-limit.login.limit=5", "zhiku.rate-limit.login-account.limit=8", "zhiku.rate-limit.chat.limit=2",
    "zhiku.rate-limit.upload.limit=1", "zhiku.rate-limit.evaluation.limit=1"})
class AuthSecurityTest extends IntegrationTest {
  static final String PASSWORD = "fictional-Passw0rd-for-tests";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccountRepository accounts;
  @Autowired PasswordEncoder passwords;
  @Autowired RateLimiter limiter;
  @Autowired ObjectMapper json;

  final UUID enterprise = UUID.randomUUID();
  final UUID otherEnterprise = UUID.randomUUID();
  UUID kb;
  String hash;

  @BeforeEach
  void seed() {
    limiter.reset();
    kb = UUID.randomUUID();
    jdbc.update("INSERT INTO knowledge_base (id, enterprise_id, name) VALUES (?, ?, ?)", kb, enterprise, "认证-" + kb);
    if (hash == null) hash = passwords.encode(PASSWORD);
  }

  UUID account(UUID enterpriseId, Role... roles) {
    var id = UUID.randomUUID();
    accounts.upsert(id, enterpriseId, "u" + id.toString().substring(0, 12), hash, List.of(roles));
    return id;
  }

  String username(UUID id) {
    return accounts.byId(id).orElseThrow().username();
  }

  MvcResult login(String username, String password, String ip) throws Exception {
    return mvc.perform(post("/api/auth/login").contentType("application/json")
        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password)))
        .with(r -> { r.setRemoteAddr(ip); return r; })).andReturn();
  }

  record Session(String access, String refresh) {}

  Session signIn(UUID id) throws Exception {
    var result = login(username(id), PASSWORD, "10.0.0." + (id.hashCode() & 0x7f));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return new Session(json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText(), refreshCookie(result));
  }

  static String refreshCookie(MvcResult result) {
    var set = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    if (set == null) return null;
    return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
  }

  MvcResult refresh(String cookie) throws Exception {
    return mvc.perform(post("/api/auth/refresh").cookie(new Cookie(AuthController.COOKIE, cookie))).andReturn();
  }

  MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String token) {
    return req.header(HttpHeaders.AUTHORIZATION, bearer(token));
  }

  @Test
  void loginIssuesShortLivedAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE, Role.KB_OPERATOR);
    var result = login(username(id).toUpperCase(), PASSWORD, "10.1.0.1");
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var body = json.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(body.get("account").get("id").asText()).isEqualTo(id.toString());
    assertThat(body.get("account").get("mustChangePassword").asBoolean()).isFalse();
    assertThat(body.toString()).doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("refresh");
    var cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(cookie).startsWith("zhiku_refresh=").contains("HttpOnly", "SameSite=Strict", "Path=/api/auth", "Secure");
    assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE account_id = ? AND refresh_hash = ?", Integer.class,
        id, refreshCookie(result))).as("refresh token stored only as a hash").isZero();
    mvc.perform(as(get("/api/me"), body.get("accessToken").asText())).andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(id.toString()))
        .andExpect(jsonPath("$.roles.length()").value(2));
  }

  @Test
  void wrongPasswordUnknownUserAndDisabledAccountGetTheSameAnswer() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE);
    for (var attempt : List.of(login(username(id), "wrong-password", "10.2.0.1"), login("nobody-here", PASSWORD, "10.2.0.2"))) {
      assertThat(attempt.getResponse().getStatus()).isEqualTo(401);
      assertThat(attempt.getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
      assertThat(attempt.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }
    accounts.update(id, enterprise, null, true);
    assertThat(login(username(id), PASSWORD, "10.2.0.3").getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
  }

  @Test
  void tokensNotBoundToASessionAreRejectedOutsideDev() throws Exception {
    var forged = TestJwt.token(enterprise, account(enterprise, Role.IT_ADMIN), Role.IT_ADMIN);
    mvc.perform(as(get("/api/me"), forged)).andExpect(status().isUnauthorized());
    var unknownSession = TestJwt.token(enterprise, UUID.randomUUID(), c -> c.claim("sid", UUID.randomUUID().toString()), TestJwt.SECRET, Role.IT_ADMIN);
    mvc.perform(as(get("/api/me"), unknownSession)).andExpect(status().isUnauthorized());
  }

  @Test
  void refreshRotatesTheCookieAndReplayAfterTheGraceWindowRevokesTheSession() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE);
    var first = signIn(id);
    var rotated = refresh(first.refresh());
    assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
    var second = refreshCookie(rotated);
    assertThat(second).isNotNull().isNotEqualTo(first.refresh());
    // 另一个标签页几乎同时用旧 cookie 刷新：给 access token，但不再下发 cookie。
    var concurrent = refresh(first.refresh());
    assertThat(concurrent.getResponse().getStatus()).isEqualTo(200);
    assertThat(concurrent.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    // 宽限期过后再拿旧 cookie 来：视为被盗用，整个会话作废。
    jdbc.update("UPDATE auth_session SET rotated_at = now() - interval '5 minutes' WHERE account_id = ?", id);
    assertThat(refresh(first.refresh()).getResponse().getStatus()).isEqualTo(401);
    assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);
    mvc.perform(as(get("/api/me"), first.access())).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRevokesTheAccessTokenAndTheRefreshCookieImmediately() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE);
    var session = signIn(id);
    mvc.perform(as(get("/api/me"), session.access())).andExpect(status().isOk());
    mvc.perform(post("/api/auth/logout").cookie(new Cookie(AuthController.COOKIE, session.refresh())))
        .andExpect(status().isNoContent())
        .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
    mvc.perform(as(get("/api/me"), session.access())).andExpect(status().isUnauthorized());
    assertThat(refresh(session.refresh()).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void rolesComeFromTheDatabaseSoDowngradeDisableAndKickOutApplyToIssuedTokens() throws Exception {
    var admin = account(enterprise, Role.IT_ADMIN, Role.EMPLOYEE);
    var target = account(enterprise, Role.IT_ADMIN, Role.EMPLOYEE);
    var adminToken = signIn(admin).access();
    var targetToken = signIn(target).access();
    mvc.perform(as(get("/api/admin/knowledge-bases"), targetToken)).andExpect(status().isOk());
    mvc.perform(as(get("/api/admin/accounts"), adminToken)).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].activeSessions").value(1));

    mvc.perform(as(patch("/api/admin/accounts/{id}", target), adminToken).contentType("application/json").content("{\"roles\":[\"EMPLOYEE\"]}"))
        .andExpect(status().isNoContent());
    mvc.perform(as(get("/api/admin/knowledge-bases"), targetToken)).andExpect(status().isForbidden());
    mvc.perform(as(get("/api/me"), targetToken)).andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(1));

    mvc.perform(as(delete("/api/admin/accounts/{id}/sessions", target), adminToken)).andExpect(status().isNoContent());
    mvc.perform(as(get("/api/me"), targetToken)).andExpect(status().isUnauthorized());

    var again = signIn(target).access();
    mvc.perform(as(patch("/api/admin/accounts/{id}", target), adminToken).contentType("application/json").content("{\"disabled\":true}"))
        .andExpect(status().isNoContent());
    mvc.perform(as(get("/api/me"), again)).andExpect(status().isUnauthorized());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id = ? AND action LIKE 'ACCOUNT_%'", Integer.class, admin))
        .isEqualTo(3);
  }

  @Test
  void refreshTellsADisabledAccountARevokedSessionAndAnExpiredOneApart() throws Exception {
    var admin = account(enterprise, Role.IT_ADMIN);
    var adminToken = signIn(admin).access();
    var expired = signIn(account(enterprise, Role.EMPLOYEE));
    jdbc.update("UPDATE auth_session SET expires_at = now() - interval '1 minute' WHERE refresh_hash = ?", AccountSessions.hash(expired.refresh()));
    assertThat(refresh(expired.refresh()).getResponse().getContentAsString()).contains("SESSION_EXPIRED");

    var kicked = account(enterprise, Role.EMPLOYEE);
    var kickedSession = signIn(kicked);
    mvc.perform(as(delete("/api/admin/accounts/{id}/sessions", kicked), adminToken)).andExpect(status().isNoContent());
    assertThat(refresh(kickedSession.refresh()).getResponse().getContentAsString()).contains("SESSION_REVOKED");

    var disabled = account(enterprise, Role.EMPLOYEE);
    var disabledSession = signIn(disabled);
    mvc.perform(as(patch("/api/admin/accounts/{id}", disabled), adminToken).contentType("application/json").content("{\"disabled\":true}"))
        .andExpect(status().isNoContent());
    var refused = refresh(disabledSession.refresh());
    assertThat(refused.getResponse().getStatus()).isEqualTo(401);
    assertThat(refused.getResponse().getContentAsString()).contains("ACCOUNT_DISABLED");
  }

  MvcResult changePassword(String token, String current, String next) throws Exception {
    return mvc.perform(as(post("/api/auth/password"), token).contentType("application/json")
        .content(json.writeValueAsString(java.util.Map.of("currentPassword", current, "newPassword", next)))).andReturn();
  }

  @Test
  void adminCreatesAnAccountWithAOneTimePasswordThatTheUserMustChange() throws Exception {
    var admin = account(enterprise, Role.IT_ADMIN);
    var adminToken = signIn(admin).access();
    var username = "new." + UUID.randomUUID().toString().substring(0, 8);
    var created = mvc.perform(as(post("/api/admin/accounts"), adminToken).contentType("application/json")
            .content("{\"username\":\"" + username.toUpperCase() + "\",\"displayName\":\"林晓（虚构）\",\"roles\":[\"EMPLOYEE\"]}"))
        .andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.username").value(username)).andReturn();
    var temporary = json.readTree(created.getResponse().getContentAsString()).get("temporaryPassword").asText();
    assertThat(temporary).hasSize(16);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE detail::text LIKE ?", Integer.class, "%" + temporary + "%"))
        .as("the one-time password never reaches the audit log").isZero();
    mvc.perform(as(post("/api/admin/accounts"), adminToken).contentType("application/json")
        .content("{\"username\":\"" + username + "\",\"roles\":[\"EMPLOYEE\"]}")).andExpect(status().isConflict());
    mvc.perform(as(post("/api/admin/accounts"), adminToken).contentType("application/json")
        .content("{\"username\":\"a b\",\"roles\":[\"EMPLOYEE\"]}")).andExpect(status().isUnprocessableEntity());
    mvc.perform(as(post("/api/admin/accounts"), adminToken).contentType("application/json")
        .content("{\"username\":\"no-roles\",\"roles\":[]}")).andExpect(status().isUnprocessableEntity());

    var first = login(username, temporary, "10.5.0.1");
    var body = json.readTree(first.getResponse().getContentAsString());
    assertThat(body.get("account").get("displayName").asText()).isEqualTo("林晓（虚构）");
    assertThat(body.get("account").get("mustChangePassword").asBoolean()).isTrue();
    var token = body.get("accessToken").asText();
    var other = json.readTree(login(username, temporary, "10.5.0.2").getResponse().getContentAsString()).get("accessToken").asText();

    assertThat(changePassword(token, "wrong-current", "brand-new-Secret-42").getResponse().getContentAsString()).contains("INVALID_CURRENT_PASSWORD");
    assertThat(changePassword(token, temporary, "short").getResponse().getStatus()).isEqualTo(422);
    assertThat(changePassword(token, temporary, username).getResponse().getStatus()).isEqualTo(422);
    assertThat(changePassword(token, temporary, "brand-new-Secret-42").getResponse().getStatus()).isEqualTo(204);
    mvc.perform(as(get("/api/me"), token)).andExpect(status().isOk());
    mvc.perform(as(get("/api/me"), other)).andExpect(status().isUnauthorized());
    assertThat(login(username, temporary, "10.5.0.3").getResponse().getStatus()).isEqualTo(401);
    var after = json.readTree(login(username, "brand-new-Secret-42", "10.5.0.4").getResponse().getContentAsString());
    assertThat(after.get("account").get("mustChangePassword").asBoolean()).isFalse();
  }

  @Test
  void adminResetsAPasswordWhichSignsTheUserOutEverywhere() throws Exception {
    var admin = account(enterprise, Role.IT_ADMIN);
    var target = account(enterprise, Role.EMPLOYEE);
    var adminToken = signIn(admin).access();
    var targetSession = signIn(target);
    var reset = mvc.perform(as(post("/api/admin/accounts/{id}/password", target), adminToken)).andExpect(status().isOk()).andReturn();
    var temporary = json.readTree(reset.getResponse().getContentAsString()).get("temporaryPassword").asText();
    mvc.perform(as(get("/api/me"), targetSession.access())).andExpect(status().isUnauthorized());
    assertThat(login(username(target), PASSWORD, "10.6.0.1").getResponse().getStatus()).isEqualTo(401);
    var again = json.readTree(login(username(target), temporary, "10.6.0.2").getResponse().getContentAsString());
    assertThat(again.get("account").get("mustChangePassword").asBoolean()).isTrue();
    mvc.perform(as(post("/api/admin/accounts/{id}/password", admin), adminToken)).andExpect(status().isUnprocessableEntity());
    mvc.perform(as(post("/api/admin/accounts"), again.get("accessToken").asText()).contentType("application/json")
        .content("{\"username\":\"sneaky\",\"roles\":[\"IT_ADMIN\"]}")).andExpect(status().isForbidden());
  }

  @Test
  void accountAdministrationStaysInsideTheEnterpriseAndNeverTargetsYourself() throws Exception {
    var admin = account(enterprise, Role.IT_ADMIN);
    var foreign = account(otherEnterprise, Role.EMPLOYEE);
    var employee = account(enterprise, Role.EMPLOYEE);
    var adminToken = signIn(admin).access();
    mvc.perform(as(patch("/api/admin/accounts/{id}", foreign), adminToken).contentType("application/json").content("{\"disabled\":true}"))
        .andExpect(status().isForbidden());
    mvc.perform(as(delete("/api/admin/accounts/{id}/sessions", UUID.randomUUID()), adminToken)).andExpect(status().isForbidden());
    mvc.perform(as(patch("/api/admin/accounts/{id}", admin), adminToken).contentType("application/json").content("{\"roles\":[]}"))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(as(get("/api/admin/accounts"), signIn(employee).access())).andExpect(status().isForbidden());
    assertThat(accounts.byId(foreign).orElseThrow().disabled()).isFalse();
  }

  @Test
  void crossTenantAccessIsStillForbiddenForLoggedInAccounts() throws Exception {
    var outsider = account(otherEnterprise, Role.IT_ADMIN, Role.KB_OPERATOR, Role.EMPLOYEE);
    // 即使有人往授权表里塞了一条跨企业的授权，令牌的企业与知识库的企业不符仍然拒绝。
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, outsider);
    var token = signIn(outsider).access();
    mvc.perform(as(get("/api/knowledge-bases/{kb}/documents", kb), token)).andExpect(status().isForbidden());
    mvc.perform(as(get("/api/knowledge-bases/{kb}/grants", kb), token)).andExpect(status().isForbidden());
    mvc.perform(as(post("/api/chat"), token).contentType("application/json")
        .content("{\"kbId\":\"" + kb + "\",\"question\":\"虚构问题\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().is4xxClientError());
    // 换成伪造企业 claim 的令牌：会话属于另一企业，没有任何访问范围。
    var sid = jdbc.queryForObject("SELECT id FROM auth_session WHERE account_id = ? LIMIT 1", UUID.class, outsider);
    var forged = TestJwt.token(enterprise, outsider, c -> c.claim("sid", sid.toString()), TestJwt.SECRET, Role.IT_ADMIN);
    mvc.perform(as(get("/api/knowledge-bases/{kb}/documents", kb), forged)).andExpect(status().isUnauthorized());
  }

  @Test
  void widgetTokensDieWithTheSessionThatIssuedThem() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, id);
    var session = signIn(id);
    var issued = mvc.perform(as(post("/api/widget-tokens"), session.access()).contentType("application/json").content("{\"kbId\":\"" + kb + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    var widget = json.readTree(issued.getResponse().getContentAsString()).get("token").asText();
    mvc.perform(as(get("/api/chat/requests/{id}", UUID.randomUUID()), widget)).andExpect(status().is(org.hamcrest.Matchers.not(401)));
    mvc.perform(post("/api/auth/logout").cookie(new Cookie(AuthController.COOKIE, session.refresh()))).andExpect(status().isNoContent());
    mvc.perform(as(get("/api/chat/requests/{id}", UUID.randomUUID()), widget)).andExpect(status().isUnauthorized());
  }

  @Test
  void loginIsRateLimitedPerSourceAddressAndPerUsername() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE);
    for (int i = 0; i < 5; i++) assertThat(login(username(id), "wrong", "10.3.0.1").getResponse().getStatus()).isEqualTo(401);
    var limited = login(username(id), PASSWORD, "10.3.0.1");
    assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    assertThat(limited.getResponse().getHeader("Retry-After")).matches("\\d+");
    assertThat(limited.getResponse().getContentAsString()).contains("RATE_LIMITED");
    // 换 IP 继续猜同一账号：用户名维度的上限（8）也会挡住。
    for (int i = 0; i < 3; i++) login(username(id), "wrong", "10.3.1." + i);
    var perAccount = login(username(id), PASSWORD, "10.3.2.1");
    assertThat(perAccount.getResponse().getStatus()).isEqualTo(429);
    assertThat(login(username(account(enterprise, Role.EMPLOYEE)), PASSWORD, "10.3.3.1").getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void chatUploadAndEvaluationAreRateLimitedPerUser() throws Exception {
    var id = account(enterprise, Role.EMPLOYEE, Role.KB_OPERATOR);
    jdbc.update("INSERT INTO kb_grant (kb_id, user_id) VALUES (?, ?)", kb, id);
    var token = signIn(id).access();
    var ask = "{\"kbId\":\"" + kb + "\",\"question\":\"虚构问题\",\"clientRequestId\":\"%s\"}";
    for (int i = 0; i < 2; i++) {
      var r = mvc.perform(as(post("/api/chat"), token).contentType("application/json").content(ask.formatted(UUID.randomUUID()))).andReturn();
      assertThat(r.getResponse().getStatus()).isNotEqualTo(429);
    }
    mvc.perform(as(post("/api/chat"), token).contentType("application/json").content(ask.formatted(UUID.randomUUID())))
        .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    var other = signIn(account(enterprise, Role.EMPLOYEE)).access();
    assertThat(mvc.perform(as(post("/api/chat"), other).contentType("application/json").content(ask.formatted(UUID.randomUUID())))
        .andReturn().getResponse().getStatus()).as("limits are per user").isNotEqualTo(429);

    var run = as(post("/api/knowledge-bases/{kb}/eval-runs", kb), token).contentType("application/json").content("{\"k\":5}");
    assertThat(mvc.perform(run).andReturn().getResponse().getStatus()).isNotEqualTo(429);
    mvc.perform(as(post("/api/knowledge-bases/{kb}/eval-runs", kb), token).contentType("application/json").content("{\"k\":5}"))
        .andExpect(status().isTooManyRequests());

    var upload = "/api/knowledge-bases/{kb}/documents/{d}/versions/{v}/content";
    var doc = UUID.randomUUID();
    var version = UUID.randomUUID();
    assertThat(mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, upload, kb, doc, version)
        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.md", "text/markdown", "# 虚构".getBytes())), token))
        .andReturn().getResponse().getStatus()).isNotEqualTo(429);
    mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, upload, kb, doc, version)
        .file(new org.springframework.mock.web.MockMultipartFile("file", "a.md", "text/markdown", "# 虚构".getBytes())), token))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void responsesCarrySecurityHeadersAndHealthChecksTheDatabase() throws Exception {
    mvc.perform(get("/api/status"))
        .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().exists("Permissions-Policy"))
        .andExpect(jsonPath("$.demoLogin").doesNotExist());
    mvc.perform(get("/api/health")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.database").value("UP"));
  }
}
