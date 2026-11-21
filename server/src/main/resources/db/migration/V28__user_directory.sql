-- 身份提供方用户目录的本地镜像：只存展示所需的姓名、邮箱、部门，供授权页按姓名搜索和显示。
-- 授权本身仍以用户 ID 为准；不在目录里的 ID 也可以授权（显示为"未同步"）。
CREATE TABLE directory_user (
  id uuid PRIMARY KEY,
  enterprise_id uuid NOT NULL,
  display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
  email text CHECK (email IS NULL OR char_length(email) <= 200),
  department text CHECK (department IS NULL OR char_length(department) <= 100),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX directory_user_enterprise_name_idx ON directory_user (enterprise_id, display_name);
