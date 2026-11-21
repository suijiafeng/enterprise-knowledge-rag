package dev.zhiku.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.zhiku.platform.RateLimitSettings;
import dev.zhiku.platform.StartupGuard;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupGuardAndLimiterTest {
  static final String GOOD = "q8VtR3m0Zk1Lw7Yp2Nc5Hs9Bd4Gf6Jx0Ua8Ie3Oo1Pr7Tl";

  @Test
  void refusesToStartWithoutAStrongSecret() {
    assertThatThrownBy(() -> StartupGuard.check(new MockEnvironment())).hasMessageContaining("ZHIKU_JWT_SECRET");
    assertThatThrownBy(() -> StartupGuard.check(new MockEnvironment().withProperty("zhiku.auth.jwt-secret", "short")))
        .hasMessageContaining("at least 32 bytes");
    assertThatThrownBy(() -> StartupGuard.check(new MockEnvironment().withProperty("zhiku.auth.jwt-secret", "change-me-change-me-change-me-change-me")))
        .hasMessageContaining("placeholder");
    StartupGuard.check(new MockEnvironment().withProperty("zhiku.auth.jwt-secret", GOOD));
  }

  @Test
  void prodProfileRejectsDevShortcuts() {
    var env = new MockEnvironment().withProperty("zhiku.auth.jwt-secret", GOOD).withProperty("spring.datasource.password", "x")
        .withProperty("zhiku.auth.dev-tokens", "true").withProperty("zhiku.demo.accounts", "true").withProperty("zhiku.auth.cookie-secure", "false");
    env.setActiveProfiles("prod");
    assertThatThrownBy(() -> StartupGuard.check(env)).hasMessageContaining("dev-tokens").hasMessageContaining("demo.accounts")
        .hasMessageContaining("cookie-secure");
    var noPassword = new MockEnvironment().withProperty("zhiku.auth.jwt-secret", GOOD);
    noPassword.setActiveProfiles("prod");
    assertThatThrownBy(() -> StartupGuard.check(noPassword)).hasMessageContaining("POSTGRES_PASSWORD");
    var ok = new MockEnvironment().withProperty("zhiku.auth.jwt-secret", GOOD).withProperty("spring.datasource.password", "x");
    ok.setActiveProfiles("prod");
    StartupGuard.check(ok);
  }

  @Test
  void fixedWindowLimiterResetsAfterTheWindow() {
    long[] now = {0};
    var rule = new RateLimitSettings.Rule(2, Duration.ofMinutes(1));
    var limiter = new dev.zhiku.platform.RateLimiterProbe(rule, () -> now[0]);
    assertThat(limiter.acquire("k")).isEmpty();
    assertThat(limiter.acquire("k")).isEmpty();
    assertThat(limiter.acquire("k")).hasValueSatisfying(d -> assertThat(d).isEqualTo(Duration.ofMinutes(1)));
    assertThat(limiter.acquire("other")).isEmpty();
    now[0] = 60_000;
    assertThat(limiter.acquire("k")).isEmpty();
  }
}
