package dev.zhiku.auth;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.platform.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IT 管理员管理本企业账号：新建、改角色、停用/启用、重置密码、踢下线。新建和重置只发放一次性临时密码（只在响应里出现一次，
 * 不落日志、不进审计），用户登录后须改成自己的密码。角色每次请求从数据库读取，
 * 所以降权和停用对已签发的 token 立即生效；踢下线撤销全部会话。不能改自己的账号，避免把自己锁在外面。
 */
@RestController
@RequestMapping("/api/admin/accounts")
class AccountAdminController {
  record AccountUpdate(Set<Role> roles, Boolean disabled) {}

  record NewAccount(String username, String displayName, Set<Role> roles) {}

  record Created(UUID id, String username, String temporaryPassword) {}

  record Reset(String temporaryPassword) {}

  private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

  private final AccessScopeResolver scopes;
  private final AccountRepository accounts;
  private final AccountSessions sessions;
  private final AuditLog audit;

  AccountAdminController(AccessScopeResolver scopes, AccountRepository accounts, AccountSessions sessions, AuditLog audit) {
    this.scopes = scopes;
    this.accounts = accounts;
    this.sessions = sessions;
    this.audit = audit;
  }

  @GetMapping
  List<AccountRepository.AccountSummary> list() {
    return accounts.list(admin().enterpriseId());
  }

  @PostMapping
  @Transactional
  ResponseEntity<Created> create(@RequestBody NewAccount request) {
    var scope = admin();
    var username = AccountSessions.normalize(request == null ? null : request.username());
    if (!USERNAME.matcher(username).matches()) {
      throw ApiException.unprocessable("Username must be 3-64 characters: lowercase letters, digits, '.', '_' or '-'");
    }
    if (request.roles() == null || request.roles().isEmpty()) throw ApiException.unprocessable("Choose at least one role");
    var displayName = request.displayName() == null || request.displayName().isBlank() ? username : request.displayName().strip();
    if (displayName.length() > 100) throw ApiException.unprocessable("Display name must be at most 100 characters");
    var id = UUID.randomUUID();
    var password = PasswordPolicy.temporary();
    if (!accounts.create(id, scope.enterpriseId(), username, sessions.encode(password), request.roles(), true)) {
      throw ApiException.conflict("Username is already taken");
    }
    accounts.saveDisplayName(id, scope.enterpriseId(), displayName);
    audit.record(scope, "ACCOUNT_CREATE", "account:" + id, "OK",
        Map.of("username", username, "roles", request.roles().stream().map(Enum::name).sorted().toList()));
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(new Created(id, username, password));
  }

  /** 重置为一次性临时密码并让该账号所有设备下线。改自己的密码走 /api/auth/password。 */
  @PostMapping("/{id}/password")
  @Transactional
  ResponseEntity<Reset> resetPassword(@PathVariable UUID id) {
    var scope = target(id);
    var password = PasswordPolicy.temporary();
    accounts.setPassword(id, sessions.encode(password), true);
    int revoked = accounts.revokeAll(id);
    audit.record(scope, "ACCOUNT_PASSWORD_RESET", "account:" + id, "OK", Map.of("sessions", revoked));
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new Reset(password));
  }

  @PatchMapping("/{id}")
  ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody AccountUpdate update) {
    var scope = target(id);
    if (update == null || (update.roles() == null && update.disabled() == null)) throw ApiException.unprocessable("Nothing to update");
    if (!accounts.update(id, scope.enterpriseId(), update.roles(), update.disabled())) throw ApiException.forbidden();
    if (Boolean.TRUE.equals(update.disabled())) accounts.revokeAll(id);
    var detail = new LinkedHashMap<String, Object>();
    if (update.roles() != null) detail.put("roles", update.roles().stream().map(Enum::name).sorted().toList());
    if (update.disabled() != null) detail.put("disabled", update.disabled());
    audit.record(scope, "ACCOUNT_UPDATE", "account:" + id, "OK", detail);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/sessions")
  ResponseEntity<Void> revokeSessions(@PathVariable UUID id) {
    var scope = target(id);
    int revoked = accounts.revokeAll(id);
    audit.record(scope, "ACCOUNT_SESSIONS_REVOKE", "account:" + id, "OK", java.util.Map.of("sessions", revoked));
    return ResponseEntity.noContent().build();
  }

  private AccessScope admin() {
    var scope = scopes.currentScope().orElseThrow(ApiException::unauthenticated);
    if (!scope.hasRole(Role.IT_ADMIN)) throw ApiException.forbidden();
    return scope;
  }

  /** 只能操作本企业的其他账号；其他企业的账号与不存在的账号同样返回 403。 */
  private AccessScope target(UUID id) {
    var scope = admin();
    var account = accounts.byId(id);
    if (account.isEmpty() || !account.get().enterpriseId().equals(scope.enterpriseId())) throw ApiException.forbidden();
    if (id.equals(scope.userId())) throw ApiException.unprocessable("Cannot change your own account");
    return scope;
  }
}
