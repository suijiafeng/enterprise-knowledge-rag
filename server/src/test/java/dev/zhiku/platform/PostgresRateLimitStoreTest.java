package dev.zhiku.platform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zhiku.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** postgres 限流存储：多个实例（这里是多个 store 对象）共用同一份窗口计数。 */
class PostgresRateLimitStoreTest extends IntegrationTest {
  @Autowired JdbcTemplate jdbc;

  static final RateLimitSettings.Rule RULE = new RateLimitSettings.Rule(3, Duration.ofMinutes(1));

  static RateLimiter limiter(RateLimitStore store) {
    return new RateLimiter(new RateLimitSettings(true, RULE, RULE, RULE, RULE, RULE, RULE), store);
  }

  @Test
  void instancesShareOneWindowAndKeysAreStoredOnlyAsHashes() {
    var key = "login:10.9.0." + UUID.randomUUID();
    var a = limiter(new PostgresRateLimitStore(jdbc));
    var b = limiter(new PostgresRateLimitStore(jdbc));
    assertThat(a.acquire(key, RULE)).isEmpty();
    assertThat(b.acquire(key, RULE)).isEmpty();
    assertThat(a.acquire(key, RULE)).isEmpty();
    var limited = b.acquire(key, RULE);
    assertThat(limited).isPresent();
    assertThat(limited.get()).isBetween(Duration.ofSeconds(1), Duration.ofMinutes(1));
    assertThat(a.acquire("login:other-" + UUID.randomUUID(), RULE)).as("other keys are counted separately").isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_window WHERE key_hash = ?", Integer.class, key)).isZero();
    assertThat(jdbc.queryForObject("SELECT count FROM rate_limit_window WHERE key_hash = ?", Integer.class,
        PostgresRateLimitStore.hash(key))).isEqualTo(4);
  }

  @Test
  void aNewWindowStartsOnceTheOldOneHasPassed() {
    var key = "chat:" + UUID.randomUUID();
    var limiter = limiter(new PostgresRateLimitStore(jdbc));
    for (int i = 0; i < 3; i++) limiter.acquire(key, RULE);
    assertThat(limiter.acquire(key, RULE)).isPresent();
    jdbc.update("UPDATE rate_limit_window SET window_start = now() - interval '2 minutes' WHERE key_hash = ?", PostgresRateLimitStore.hash(key));
    assertThat(limiter.acquire(key, RULE)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count FROM rate_limit_window WHERE key_hash = ?", Integer.class,
        PostgresRateLimitStore.hash(key))).isEqualTo(1);
  }

  @Test
  void concurrentHitsAreCountedExactlyOnce() throws Exception {
    var key = "upload:" + UUID.randomUUID();
    var store = new PostgresRateLimitStore(jdbc);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = IntStream.range(0, 40).mapToObj(i -> pool.submit(() -> store.hit(key, 60_000))).toList();
      for (var f : futures) f.get();
    }
    assertThat(jdbc.queryForObject("SELECT count FROM rate_limit_window WHERE key_hash = ?", Integer.class,
        PostgresRateLimitStore.hash(key))).isEqualTo(40);
  }

  @Test
  void anUnavailableDatabaseLetsRequestsThrough() throws Exception {
    var broken = Mockito.mock(DataSource.class);
    Mockito.when(broken.getConnection()).thenThrow(new java.sql.SQLException("down"));
    var limiter = limiter(new PostgresRateLimitStore(new JdbcTemplate(broken)));
    for (int i = 0; i < 10; i++) assertThat(limiter.acquire("login:x", RULE)).isEmpty();
  }

  @Test
  void memoryStoreIsTheDefault(@Autowired RateLimiter limiter) {
    assertThat(limiter.storeName()).isEqualTo("memory");
  }
}
