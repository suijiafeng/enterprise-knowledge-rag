package dev.zhiku.auth;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 用户名密码登录、刷新令牌轮换与退出。
 * access token 短期有效并携带会话 id（sid）；每个请求都核对会话未撤销、账号未停用，角色以数据库为准。
 */
@Service
public class AccountSessions {
  public static final String SESSION = "sid";
  public static final String ACCESS = "access";
  /** 并发刷新（多个标签页同时恢复）的宽限期；超出后重放旧令牌视为盗用。 */
  static final Duration REUSE_GRACE = Duration.ofSeconds(30);

  public record AccountView(UUID id, String username, String displayName, Set<Role> roles) {}

  public record Issued(String accessToken, Instant expiresAt, AccountView account, String refreshToken, Instant refreshExpiresAt) {}

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String CACHE = AccountSessions.class.getName() + ".active.";

  private final AccountRepository accounts;
  private final ObjectProvider<JwtEncoder> encoder;
  private final JwtSettings settings;
  private final AuditLog audit;
  private final PasswordEncoder passwords;
  /** 用户名不存在时也做一次同等代价的比对，避免靠响应时间枚举用户名。 */
  private final String dummyHash;

  public AccountSessions(AccountRepository accounts, ObjectProvider<JwtEncoder> encoder, JwtSettings settings, AuditLog audit,
      PasswordEncoder passwords) {
    this.accounts = accounts;
    this.encoder = encoder;
    this.settings = settings;
    this.audit = audit;
    this.passwords = passwords;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public static PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  static ApiException invalidCredentials() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
  }

  static ApiException invalidSession() {
    return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired, sign in again");
  }

  public static String normalize(String username) {
    return username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
  }

  public Issued login(String username, String password) {
    var account = accounts.byUsername(normalize(username));
    boolean matches = passwords.matches(password == null ? "" : password, account.map(AccountRepository.Account::passwordHash).orElse(dummyHash));
    if (account.isEmpty() || !matches || account.get().disabled()) throw invalidCredentials();
    var a = account.get();
    var session = UUID.randomUUID();
    var refresh = randomToken();
    var refreshExpires = Instant.now().plus(settings.refreshTtl());
    accounts.createSession(session, a.id(), hash(refresh), refreshExpires);
    audit.record(scope(a), "LOGIN", "account:" + a.id(), "OK", Map.of());
    return issue(a, session, refresh, refreshExpires);
  }

  /** 轮换刷新令牌。返回的 refreshToken 为 null 表示宽限期内的并发刷新：沿用浏览器里已更新的 cookie。 */
  public Issued refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 200) throw invalidSession();
    var presented = hash(refreshToken);
    var row = accounts.sessionByHash(presented).orElseThrow(AccountSessions::invalidSession);
    var account = accounts.byId(row.accountId()).orElseThrow(AccountSessions::invalidSession);
    if (row.revokedAt() != null || row.expiresAt().isBefore(Instant.now()) || account.disabled()) throw invalidSession();
    if (!row.current()) {
      if (row.rotatedAt() != null && row.rotatedAt().isAfter(Instant.now().minus(REUSE_GRACE))) {
        return issue(account, row.id(), null, row.expiresAt());
      }
      accounts.revoke(row.id());
      audit.record(scope(account), "REFRESH_REUSE", "session:" + row.id(), "REVOKED", Map.of());
      throw invalidSession();
    }
    var next = randomToken();
    var expires = Instant.now().plus(settings.refreshTtl());
    if (!accounts.rotate(row.id(), presented, hash(next), expires)) {
      // 另一个请求刚刚轮换了它：按并发刷新处理。
      return issue(account, row.id(), null, row.expiresAt());
    }
    return issue(account, row.id(), next, expires);
  }

  /** 撤销 cookie 对应的会话，或 bearer token 所属的会话；都没有时什么也不做。 */
  public void logout(String refreshToken, Jwt bearer) {
    Optional<UUID> session = Optional.empty();
    if (refreshToken != null && !refreshToken.isBlank() && refreshToken.length() <= 200) {
      session = accounts.sessionByHash(hash(refreshToken)).map(AccountRepository.SessionRow::id);
    }
    if (session.isEmpty() && bearer != null && bearer.hasClaim(SESSION)) {
      session = parse(bearer.getClaimAsString(SESSION));
    }
    session.ifPresent(accounts::revoke);
  }

  /** 绑定会话的令牌在本请求内是否仍然有效；结果按请求缓存，避免认证和授权各查一次。 */
  public Optional<AccountRepository.ActiveSession> check(Jwt jwt) {
    var sid = parse(jwt.getClaimAsString(SESSION));
    var sub = parse(jwt.getSubject());
    if (sid.isEmpty() || sub.isEmpty()) return Optional.empty();
    var attrs = RequestContextHolder.getRequestAttributes();
    var key = CACHE + sid.get();
    if (attrs != null && attrs.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof AccountRepository.ActiveSession cached) {
      return Optional.of(cached);
    }
    var active = accounts.active(sub.get(), sid.get());
    if (attrs != null) active.ifPresent(a -> attrs.setAttribute(key, a, RequestAttributes.SCOPE_REQUEST));
    return active;
  }

  private Issued issue(AccountRepository.Account a, UUID session, String refresh, Instant refreshExpires) {
    var jwtEncoder = encoder.getIfAvailable();
    if (jwtEncoder == null) throw ApiException.unavailable("Token signing is not configured");
    var now = Instant.now();
    var expires = now.plus(settings.accessTtl());
    var claims = JwtClaimsSet.builder()
        .issuer(settings.issuer()).audience(List.of(settings.audience()))
        .subject(a.id().toString()).issuedAt(now).expiresAt(expires)
        .claim("enterprise_id", a.enterpriseId().toString())
        .claim("roles", a.roles().stream().map(Enum::name).sorted().toList())
        .claim(SESSION, session.toString())
        .claim(JwtAccessScopeResolver.TOKEN_USE, ACCESS)
        .build();
    var token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    return new Issued(token, expires, new AccountView(a.id(), a.username(), a.displayName(), a.roles()), refresh, refreshExpires);
  }

  private static AccessScope scope(AccountRepository.Account a) {
    return new AccessScope(a.enterpriseId(), a.id(), Set.of(), EnumSet.copyOf(a.roles().isEmpty() ? EnumSet.noneOf(Role.class) : a.roles()));
  }

  private static Optional<UUID> parse(String value) {
    try {
      return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
