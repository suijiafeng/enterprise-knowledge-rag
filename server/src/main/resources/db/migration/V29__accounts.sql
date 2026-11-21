-- 本地账号与登录会话。密码只存 bcrypt 哈希；刷新令牌只存 SHA-256 哈希。
-- 角色每次请求从这里读取，降权和停用立即生效；撤销会话使其 access token 立即失效。
CREATE TABLE app_account (
  id uuid PRIMARY KEY,
  enterprise_id uuid NOT NULL,
  username text NOT NULL CHECK (char_length(username) BETWEEN 3 AND 64 AND username = lower(username)),
  password_hash text NOT NULL,
  roles text[] NOT NULL DEFAULT '{}',
  disabled boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_account_username ON app_account (username);
CREATE INDEX app_account_enterprise ON app_account (enterprise_id);

CREATE TABLE auth_session (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES app_account (id) ON DELETE CASCADE,
  refresh_hash text NOT NULL UNIQUE,
  -- 上一个刷新令牌：短时间内重放视为并发刷新，超出宽限期视为令牌被盗用并撤销整个会话。
  previous_hash text,
  rotated_at timestamptz,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_session_account ON auth_session (account_id) WHERE revoked_at IS NULL;
CREATE INDEX auth_session_previous ON auth_session (previous_hash) WHERE previous_hash IS NOT NULL;
