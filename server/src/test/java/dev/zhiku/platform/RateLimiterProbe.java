package dev.zhiku.platform;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

/** 测试用：带可控时钟的 RateLimiter。 */
public final class RateLimiterProbe {
  private final RateLimiter limiter;
  private final RateLimitSettings.Rule rule;

  public RateLimiterProbe(RateLimitSettings.Rule rule, LongSupplier clock) {
    this.rule = rule;
    this.limiter = new RateLimiter(new RateLimitSettings(true, rule, rule, rule, rule, rule, rule), clock);
  }

  public Optional<Duration> acquire(String key) {
    return limiter.acquire(key, rule);
  }
}
