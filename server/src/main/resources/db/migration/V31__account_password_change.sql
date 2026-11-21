-- 管理员新建账号或重置密码后发放一次性临时密码；用户首次登录后需改成自己的密码。
-- 服务端只记录状态并在登录响应里返回，由管理端强制弹出修改密码；其他接口不因此拒绝（见 docs/security.md）。
ALTER TABLE app_account ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;
