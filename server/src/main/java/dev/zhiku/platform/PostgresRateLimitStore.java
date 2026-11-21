package dev.zhiku.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多实例共享的固定窗口计数：一条 upsert 原子地计数或开新窗口，时间以数据库 now() 为准（不受各实例时钟漂移影响）。
 * 数据库不可用时放行并告警（fail-open）：限流是防滥用的一道防线，不能因为它把所有登录都挡住；网关层限流仍然有效。
 */
public class PostgresRateLimitStore implements RateLimitStore {
  private static final Logger log = LoggerFactory.getLogger(PostgresRateLimitStore.class);
  private static final int SWEEP_EVERY = 1000;
  private static final long WARN_EVERY_MS = 60_000;

  private static final String HIT = """
      INSERT INTO rate_limit_window AS w (key_hash, window_start, window_ms, count) VALUES (?, now(), ?, 1)
      ON CONFLICT (key_hash) DO UPDATE SET
        count = CASE WHEN w.window_start + w.window_ms * interval '1 millisecond' <= now() THEN 1 ELSE w.count + 1 END,
        window_start = CASE WHEN w.window_start + w.window_ms * interval '1 millisecond' <= now() THEN now() ELSE w.window_start END,
        window_ms = EXCLUDED.window_ms
      RETURNING count, GREATEST(0, floor(extract(epoch FROM (window_start + window_ms * interval '1 millisecond' - now())) * 1000))::bigint AS remaining
      """;

  private final JdbcTemplate jdbc;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong lastWarning = new AtomicLong();

  public PostgresRateLimitStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Hit hit(String key, long windowMillis) {
    try {
      if (hits.incrementAndGet() % SWEEP_EVERY == 0) {
        jdbc.update("DELETE FROM rate_limit_window WHERE window_start + window_ms * interval '1 millisecond' < now()");
      }
      return jdbc.queryForObject(HIT, (rs, i) -> new Hit(rs.getInt("count"), rs.getLong("remaining")), hash(key), windowMillis);
    } catch (DataAccessException e) {
      long now = System.currentTimeMillis();
      long last = lastWarning.get();
      if (now - last >= WARN_EVERY_MS && lastWarning.compareAndSet(last, now)) {
        log.warn("Rate limit store unavailable, allowing requests: {}", e.getClass().getSimpleName());
      }
      return new Hit(0, 0);
    }
  }

  @Override
  public void clear() {
    jdbc.update("DELETE FROM rate_limit_window");
  }

  static String hash(String key) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
