package dev.zhiku.auth;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.platform.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IT 管理员管理本企业账号：改角色、停用、踢下线。角色每次请求从数据库读取，
 * 所以降权和停用对已签发的 token 立即生效；踢下线撤销全部会话。不能改自己的账号，避免把自己锁在外面。
 */
@RestController
@RequestMapping("/api/admin/accounts")
class AccountAdminController {
  record AccountUpdate(Set<Role> roles, Boolean disabled) {}

  private final AccessScopeResolver scopes;
  private final AccountRepository accounts;
  private final AuditLog audit;

  AccountAdminController(AccessScopeResolver scopes, AccountRepository accounts, AuditLog audit) {
    this.scopes = scopes;
    this.accounts = accounts;
    this.audit = audit;
  }

  @GetMapping
  List<AccountRepository.AccountSummary> list() {
    return accounts.list(admin().enterpriseId());
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
