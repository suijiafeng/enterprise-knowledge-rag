package dev.zhiku.auth;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示账号，只在 dev 配置（zhiku.demo.accounts=true）下写入；生产配置启动时会拒绝该开关。
 * 用户 id 与 scripts/dev-seed.sql 的虚构用户一致，授权关系由种子数据决定。
 */
@Component
@ConditionalOnProperty(name = "zhiku.demo.accounts", havingValue = "true")
class DemoAccounts implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DemoAccounts.class);
  static final UUID ENTERPRISE = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

  record Demo(UUID id, String username, String displayName, List<Role> roles) {}

  static final List<Demo> ACCOUNTS = List.of(
      new Demo(UUID.fromString("00000000-0000-4000-8000-0000000000a1"), "admin", "林知远（示例）", List.of(Role.IT_ADMIN, Role.KB_OPERATOR, Role.EMPLOYEE)),
      new Demo(UUID.fromString("00000000-0000-4000-8000-0000000000a2"), "operator", "周若溪（示例）", List.of(Role.KB_OPERATOR, Role.EMPLOYEE)),
      new Demo(UUID.fromString("00000000-0000-4000-8000-0000000000a3"), "employee", "陈一鸣（示例）", List.of(Role.EMPLOYEE)));

  private final AccountRepository accounts;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final String password;

  DemoAccounts(AccountRepository accounts, JdbcTemplate jdbc, PasswordEncoder passwords, @Value("${zhiku.demo.password}") String password) {
    this.accounts = accounts;
    this.jdbc = jdbc;
    this.passwords = passwords;
    if (password == null || password.length() < 8) throw new IllegalStateException("zhiku.demo.password must be at least 8 characters");
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (var demo : ACCOUNTS) {
      accounts.upsert(demo.id(), ENTERPRISE, demo.username(), passwords.encode(password), demo.roles());
      jdbc.update("INSERT INTO directory_user (id, enterprise_id, display_name) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
          demo.id(), ENTERPRISE, demo.displayName());
    }
    log.warn("Demo accounts enabled (admin / operator / employee). Never enable zhiku.demo.accounts outside local development.");
  }
}
