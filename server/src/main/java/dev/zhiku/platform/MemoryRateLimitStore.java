package dev.zhiku.platform;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 进程内固定窗口计数：重启即清零，各实例各算各的。键数量过多时顺手清掉已过期的窗口。 */
public class MemoryRateLimitStore implements RateLimitStore {
  private static final int SWEEP_AT = 50_000;

  private record Window(long start, long length, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public MemoryRateLimitStore(LongSupplier clock) {
    this.clock = clock;
  }

  @Override
  public Hit hit(String key, long windowMillis) {
    long now = clock.getAsLong();
    if (windows.size() > SWEEP_AT) windows.values().removeIf(w -> now - w.start() >= w.length());
    var w = windows.compute(key, (k, old) -> old == null || now - old.start() >= windowMillis
        ? new Window(now, windowMillis, 1) : new Window(old.start(), windowMillis, old.count() + 1));
    return new Hit(w.count(), w.start() + windowMillis - now);
  }

  @Override
  public void clear() {
    windows.clear();
  }
}
