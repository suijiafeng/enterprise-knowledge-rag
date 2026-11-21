package dev.zhiku.platform;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 固定窗口计数。超限时返回需要等待的时长；键数量过多时顺手清掉已过期的窗口。 */
@Component
@EnableConfigurationProperties(RateLimitSettings.class)
public class RateLimiter {
  private static final int SWEEP_AT = 50_000;

  private record Window(long start, long length, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final RateLimitSettings settings;
  private final LongSupplier clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RateLimiter(RateLimitSettings settings) {
    this(settings, System::currentTimeMillis);
  }

  RateLimiter(RateLimitSettings settings, LongSupplier clock) {
    this.settings = settings;
    this.clock = clock;
  }

  public boolean enabled() {
    return settings.enabled();
  }

  public RateLimitSettings settings() {
    return settings;
  }

  /** 计入一次请求；超出上限时返回 Retry-After。 */
  public Optional<Duration> acquire(String key, RateLimitSettings.Rule rule) {
    if (!settings.enabled() || rule == null || rule.limit() <= 0) return Optional.empty();
    long now = clock.getAsLong();
    long length = rule.window().toMillis();
    if (windows.size() > SWEEP_AT) windows.values().removeIf(w -> now - w.start() >= w.length());
    var w = windows.compute(key, (k, old) -> old == null || now - old.start() >= length
        ? new Window(now, length, 1) : new Window(old.start(), length, old.count() + 1));
    if (w.count() <= rule.limit()) return Optional.empty();
    return Optional.of(Duration.ofMillis(Math.max(1000, w.start() + length - now)));
  }

  public void reset() {
    windows.clear();
  }
}
