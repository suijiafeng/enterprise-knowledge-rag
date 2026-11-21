package dev.zhiku.auth;

import dev.zhiku.platform.RateLimitedException;
import dev.zhiku.platform.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录、刷新与退出。access token 只在响应体里返回，由页面放在内存；
 * 刷新令牌只放在 HttpOnly + SameSite=Strict、路径限定为 /api/auth 的 cookie 里，脚本读不到。
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {
  static final String COOKIE = "zhiku_refresh";

  record LoginRequest(@NotNull @Size(min = 1, max = 64) String username, @NotNull @Size(min = 1, max = 200) String password) {}

  record Account(UUID id, String username, String displayName, Set<Role> roles, boolean mustChangePassword) {}

  record PasswordChange(@NotNull @Size(max = 200) String currentPassword, @NotNull @Size(max = 200) String newPassword) {}

  record TokenResponse(String accessToken, String tokenType, Instant expiresAt, Account account) {}

  private final AccountSessions sessions;
  private final RateLimiter limiter;
  private final JwtSettings settings;

  AuthController(AccountSessions sessions, RateLimiter limiter, JwtSettings settings) {
    this.sessions = sessions;
    this.limiter = limiter;
    this.settings = settings;
  }

  @PostMapping("/login")
  ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    // 来源 IP 的限流在 RateLimitFilter；这里再按用户名限流，挡住分散 IP 对同一账号的猜测。
    limiter.acquire("login-account:" + AccountSessions.normalize(request.username()), limiter.settings().loginAccount())
        .ifPresent(retry -> { throw new RateLimitedException(retry); });
    return respond(sessions.login(request.username(), request.password()));
  }

  @PostMapping("/refresh")
  ResponseEntity<TokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String refreshToken) {
    return respond(sessions.refresh(refreshToken));
  }

  /** 本人改密只接受绑定会话的登录令牌（不接受开发令牌或 widget 令牌）；按账号限流，挡住借会话猜当前密码。 */
  @PostMapping("/password")
  ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange request) {
    var jwt = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t ? t.getToken() : null;
    if (jwt == null || !jwt.hasClaim(AccountSessions.SESSION)) throw dev.zhiku.platform.ApiException.forbidden();
    var account = UUID.fromString(jwt.getSubject());
    limiter.acquire("password:" + account, limiter.settings().loginAccount()).ifPresent(retry -> { throw new RateLimitedException(retry); });
    sessions.changePassword(account, UUID.fromString(jwt.getClaimAsString(AccountSessions.SESSION)), request.currentPassword(), request.newPassword());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken) {
    var bearer = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t ? t.getToken() : null;
    sessions.logout(refreshToken, bearer);
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
  }

  private ResponseEntity<TokenResponse> respond(AccountSessions.Issued issued) {
    var a = issued.account();
    var body = new TokenResponse(issued.accessToken(), "Bearer", issued.expiresAt(), new Account(a.id(), a.username(), a.displayName(), a.roles(), a.mustChangePassword()));
    var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (issued.refreshToken() != null) {
      response.header(HttpHeaders.SET_COOKIE, cookie(issued.refreshToken(), Duration.between(Instant.now(), issued.refreshExpiresAt())).toString());
    }
    return response.body(body);
  }

  private ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(settings.cookieSecure()).sameSite("Strict")
        .path("/api/auth").maxAge(maxAge).build();
  }
}
