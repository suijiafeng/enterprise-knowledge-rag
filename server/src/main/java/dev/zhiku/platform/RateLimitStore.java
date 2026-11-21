package dev.zhiku.platform;

/**
 * 限流计数的存放处。默认 memory（单实例、开发用）；多实例部署用 postgres，让所有实例共用同一份窗口计数。
 * 实现需要原子地「计入一次并返回当前窗口的计数」。
 */
public interface RateLimitStore {
  /** count 为本次计入后的窗口计数；remainingMillis 为当前窗口剩余时长。 */
  record Hit(int count, long remainingMillis) {}

  Hit hit(String key, long windowMillis);

  void clear();
}
