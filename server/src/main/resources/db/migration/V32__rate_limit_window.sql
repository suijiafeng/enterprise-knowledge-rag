-- 多实例共享的限流窗口（zhiku.rate-limit.store=postgres 时使用；默认 memory 不写这张表）。
-- 键只存 SHA-256，不保存 IP、用户名或用户 id 明文。
CREATE TABLE rate_limit_window (
  key_hash text PRIMARY KEY,
  window_start timestamptz NOT NULL,
  window_ms bigint NOT NULL CHECK (window_ms > 0),
  count integer NOT NULL
);
CREATE INDEX rate_limit_window_expiry ON rate_limit_window (window_start);
