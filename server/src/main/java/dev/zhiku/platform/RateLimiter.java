package dev.zhiku.platform;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 固定窗口限流。超限时返回需要等待的时长。计数放在可替换的 {@link RateLimitStore} 里：
 * zhiku.rate-limit.store=memory（默认，单实例）或 postgres（多实例共享）。
 */
@Component
@EnableConfigurationProperties(RateLimitSettings.class)
public class RateLimiter {
  private final RateLimitSettings settings;
  private final RateLimitStore store;

  @org.springframework.beans.factory.annotation.Autowired
  public RateLimiter(RateLimitSettings settings, JdbcTemplate jdbc, @Value("${zhiku.rate-limit.store:memory}") String store) {
    this(settings, switch (store.strip().toLowerCase(java.util.Locale.ROOT)) {
      case "memory" -> new MemoryRateLimitStore(System::currentTimeMillis);
      case "postgres" -> new PostgresRateLimitStore(jdbc);
      default -> throw new IllegalStateException("zhiku.rate-limit.store must be memory or postgres, got: " + store);
    });
  }

  RateLimiter(RateLimitSettings settings, LongSupplier clock) {
    this(settings, new MemoryRateLimitStore(clock));
  }

  RateLimiter(RateLimitSettings settings, RateLimitStore store) {
    this.settings = settings;
    this.store = store;
  }

  public boolean enabled() {
    return settings.enabled();
  }

  public RateLimitSettings settings() {
    return settings;
  }

  public String storeName() {
    return store instanceof PostgresRateLimitStore ? "postgres" : "memory";
  }

  /** 计入一次请求；超出上限时返回 Retry-After（至少 1 秒）。 */
  public Optional<Duration> acquire(String key, RateLimitSettings.Rule rule) {
    if (!settings.enabled() || rule == null || rule.limit() <= 0) return Optional.empty();
    var hit = store.hit(key, rule.window().toMillis());
    if (hit.count() <= rule.limit()) return Optional.empty();
    return Optional.of(Duration.ofMillis(Math.max(1000, hit.remainingMillis())));
  }

  public void reset() {
    store.clear();
  }
}
