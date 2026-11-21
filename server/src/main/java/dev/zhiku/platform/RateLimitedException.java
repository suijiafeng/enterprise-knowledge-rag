package dev.zhiku.platform;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 429，带 Retry-After（秒）。 */
public class RateLimitedException extends ApiException {
  private final Duration retryAfter;

  public RateLimitedException(Duration retryAfter) {
    super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests, retry later");
    this.retryAfter = retryAfter;
  }

  public long retryAfterSeconds() {
    return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
  }
}
