package dev.zhiku.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 令牌设置。devTokens=true 时接受不绑定登录会话的令牌（scripts/dev-token.mjs、测试），只能在 dev 配置里打开；
 * 生产只接受 /api/auth/login 签发、绑定会话的令牌。
 */
@ConfigurationProperties("zhiku.auth")
public record JwtSettings(String jwtSecret, String issuer, String audience,
    @DefaultValue("PT15M") Duration accessTtl, @DefaultValue("P7D") Duration refreshTtl,
    @DefaultValue("false") boolean devTokens, @DefaultValue("true") boolean cookieSecure) {
  static final int MIN_SECRET_BYTES = 32;

  public boolean configured() {
    return jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
  }
}
