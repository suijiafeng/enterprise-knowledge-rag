package dev.zhiku.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 本地账号与登录会话。只保存哈希，从不保存明文密码或刷新令牌。 */
@Repository
public class AccountRepository {
  public record Account(UUID id, UUID enterpriseId, String username, String passwordHash, Set<Role> roles, boolean disabled,
      String displayName, boolean mustChangePassword) {}

  public record AccountSummary(UUID id, String username, String displayName, Set<Role> roles, boolean disabled, int activeSessions,
      boolean mustChangePassword) {}

  /** 会话仍有效时的账号状态：角色以数据库为准。 */
  public record ActiveSession(UUID sessionId, UUID accountId, UUID enterpriseId, Set<Role> roles) {}

  public record SessionRow(UUID id, UUID accountId, boolean current, Instant rotatedAt, Instant expiresAt, Instant revokedAt) {}

  private final JdbcTemplate jdbc;

  public AccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  static Set<Role> roles(java.sql.Array array) throws SQLException {
    var roles = EnumSet.noneOf(Role.class);
    if (array == null) return roles;
    for (Object name : (Object[]) array.getArray()) {
      for (var role : Role.values()) if (role.name().equals(name)) roles.add(role);
    }
    return roles;
  }

  static String roleList(Collection<Role> roles) {
    return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static final String ACCOUNT = """
      SELECT a.id, a.enterprise_id, a.username, a.password_hash, a.roles, a.disabled, d.display_name, a.must_change_password
      FROM app_account a LEFT JOIN directory_user d ON d.id = a.id AND d.enterprise_id = a.enterprise_id
      """;

  private static Account account(ResultSet rs, int row) throws SQLException {
    return new Account(rs.getObject("id", UUID.class), rs.getObject("enterprise_id", UUID.class), rs.getString("username"),
        rs.getString("password_hash"), roles(rs.getArray("roles")), rs.getBoolean("disabled"), rs.getString("display_name"),
        rs.getBoolean("must_change_password"));
  }

  public Optional<Account> byUsername(String username) {
    return jdbc.query(ACCOUNT + " WHERE a.username = ?", AccountRepository::account, username).stream().findFirst();
  }

  public Optional<Account> byId(UUID id) {
    return jdbc.query(ACCOUNT + " WHERE a.id = ?", AccountRepository::account, id).stream().findFirst();
  }

  public List<AccountSummary> list(UUID enterpriseId) {
    return jdbc.query("""
        SELECT a.id, a.username, d.display_name, a.roles, a.disabled, a.must_change_password,
          (SELECT count(*) FROM auth_session s WHERE s.account_id = a.id AND s.revoked_at IS NULL AND s.expires_at > now()) AS active
        FROM app_account a LEFT JOIN directory_user d ON d.id = a.id AND d.enterprise_id = a.enterprise_id
        WHERE a.enterprise_id = ? ORDER BY a.username
        """, (rs, i) -> new AccountSummary(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
        roles(rs.getArray("roles")), rs.getBoolean("disabled"), rs.getInt("active"), rs.getBoolean("must_change_password")), enterpriseId);
  }

  /** 新建或覆盖账号（演示账号、测试）。 */
  public void upsert(UUID id, UUID enterpriseId, String username, String passwordHash, Collection<Role> roles) {
    jdbc.update("""
        INSERT INTO app_account (id, enterprise_id, username, password_hash, roles) VALUES (?, ?, ?, ?, string_to_array(?, ','))
        ON CONFLICT (id) DO UPDATE SET enterprise_id = EXCLUDED.enterprise_id, username = EXCLUDED.username,
          password_hash = EXCLUDED.password_hash, roles = EXCLUDED.roles, disabled = false, updated_at = now()
        """, id, enterpriseId, username, passwordHash, roleList(roles));
  }

  /** 管理员新建账号：用户名已被占用（含其他企业）时返回 false。 */
  public boolean create(UUID id, UUID enterpriseId, String username, String passwordHash, Collection<Role> roles, boolean mustChangePassword) {
    return jdbc.update("""
        INSERT INTO app_account (id, enterprise_id, username, password_hash, roles, must_change_password)
        VALUES (?, ?, ?, ?, string_to_array(?, ','), ?) ON CONFLICT (username) DO NOTHING
        """, id, enterpriseId, username, passwordHash, roleList(roles), mustChangePassword) == 1;
  }

  /** 账号的显示名放在通讯录（directory_user，与账号同 id），问答记录与授权页面都从这里取名字。 */
  public void saveDisplayName(UUID id, UUID enterpriseId, String displayName) {
    jdbc.update("""
        INSERT INTO directory_user (id, enterprise_id, display_name) VALUES (?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name, updated_at = now() WHERE directory_user.enterprise_id = EXCLUDED.enterprise_id
        """, id, enterpriseId, displayName);
  }

  public void setPassword(UUID id, String passwordHash, boolean mustChangePassword) {
    jdbc.update("UPDATE app_account SET password_hash = ?, must_change_password = ?, updated_at = now() WHERE id = ?",
        passwordHash, mustChangePassword, id);
  }

  /** 撤销该账号除 keep 以外的全部会话（改密后保留当前设备）。 */
  public int revokeOthers(UUID accountId, UUID keep) {
    return jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE account_id = ? AND id <> ? AND revoked_at IS NULL", accountId, keep);
  }

  public boolean update(UUID id, UUID enterpriseId, Collection<Role> roles, Boolean disabled) {
    return jdbc.update("""
        UPDATE app_account SET roles = COALESCE(string_to_array(?, ','), roles), disabled = COALESCE(?, disabled), updated_at = now()
        WHERE id = ? AND enterprise_id = ?
        """, roles == null ? null : roleList(roles), disabled, id, enterpriseId) == 1;
  }

  public void createSession(UUID id, UUID accountId, String refreshHash, Instant expiresAt) {
    jdbc.update("DELETE FROM auth_session WHERE account_id = ? AND (expires_at < now() OR revoked_at < now() - interval '1 day')", accountId);
    jdbc.update("INSERT INTO auth_session (id, account_id, refresh_hash, expires_at) VALUES (?, ?, ?, ?)",
        id, accountId, refreshHash, Timestamp.from(expiresAt));
  }

  public Optional<SessionRow> sessionByHash(String hash) {
    return jdbc.query("""
        SELECT id, account_id, refresh_hash = ? AS current, rotated_at, expires_at, revoked_at
        FROM auth_session WHERE refresh_hash = ? OR previous_hash = ?
        """, (rs, i) -> new SessionRow(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getBoolean("current"),
        instant(rs, "rotated_at"), instant(rs, "expires_at"), instant(rs, "revoked_at")), hash, hash, hash).stream().findFirst();
  }

  /** 只有仍持有当前刷新令牌的一方能轮换成功；并发的另一方拿到 false。 */
  public boolean rotate(UUID sessionId, String oldHash, String newHash, Instant expiresAt) {
    return jdbc.update("""
        UPDATE auth_session SET previous_hash = refresh_hash, refresh_hash = ?, rotated_at = now(), last_used_at = now(), expires_at = ?
        WHERE id = ? AND refresh_hash = ? AND revoked_at IS NULL AND expires_at > now()
        """, newHash, Timestamp.from(expiresAt), sessionId, oldHash) == 1;
  }

  public void revoke(UUID sessionId) {
    jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
  }

  public int revokeAll(UUID accountId) {
    return jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE account_id = ? AND revoked_at IS NULL", accountId);
  }

  public Optional<ActiveSession> active(UUID accountId, UUID sessionId) {
    return jdbc.query("""
        SELECT a.enterprise_id, a.roles FROM auth_session s JOIN app_account a ON a.id = s.account_id
        WHERE s.id = ? AND a.id = ? AND NOT a.disabled AND s.revoked_at IS NULL AND s.expires_at > now()
        """, (rs, i) -> new ActiveSession(sessionId, accountId, rs.getObject("enterprise_id", UUID.class), roles(rs.getArray("roles"))),
        sessionId, accountId).stream().findFirst();
  }
}
