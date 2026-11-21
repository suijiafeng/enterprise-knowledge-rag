# 账号、会话与限流

本地账号（用户名 + 密码）是目前唯一的登录方式。下面说明账号怎么开、密码怎么换、会话什么时候失效，以及多实例时限流怎么配。

## 账号

- IT 管理员在管理端「用户与权限 → 账号」新建账号：填用户名（3–64 位小写字母、数字或 `. _ -`）、显示名和角色。显示名同时写入用户目录，授权时可以按名字搜到。
- 新建和重置密码都只发**一次性临时密码**：16 位、去掉易混字符，只出现在那一次响应里（`Cache-Control: no-store`），不写日志、不进审计。管理员当面或经企业内部渠道转交。
- 临时密码登录后，登录响应带 `mustChangePassword: true`，管理端弹出不能关闭的「请先设置自己的密码」（只能改密或退出）。服务端只记录这个状态，**不因此拒绝其他接口**：强制只在管理端。需要服务端强制时，在 `JwtAccessScopeResolver` 里对 `must_change_password` 的账号只放行 `/api/auth/*`。
- 本人改密（右上角账号菜单 →「修改密码」，`POST /api/auth/password`）：核对当前密码；新密码 10–200 位、不能等于用户名、不能是几个字符的重复；成功后其他设备下线，当前设备保持登录。按账号限流。
- 停用立即撤销全部会话；重置密码同样让该账号所有设备下线。管理员不能停用、降权或重置自己，避免把自己锁在外面（自己的密码走本人改密）。
- 所有账号操作写入审计：`ACCOUNT_CREATE`、`ACCOUNT_UPDATE`、`ACCOUNT_PASSWORD_RESET`、`ACCOUNT_SESSIONS_REVOKE`、`PASSWORD_CHANGE`（含失败）。
- 生产环境没有默认账号。第一个 IT 管理员需要手工写入 `app_account`（bcrypt 哈希，cost 12），之后的账号都在管理端开。

## 会话失效的原因

刷新令牌换不到新 access token 时，服务端用错误码说明原因，管理端回到登录页并提示一次：

| 错误码 | 含义 | 登录页提示 |
| --- | --- | --- |
| `ACCOUNT_DISABLED` | 账号被管理员停用 | 账号已被管理员停用，如有疑问请联系 IT 管理员 |
| `SESSION_REVOKED` | 在别处退出、被管理员踢下线、改密 / 重置后下线，或刷新令牌在宽限期后被重放 | 登录已在其他地方退出或被管理员撤销，请重新登录 |
| `SESSION_EXPIRED` | 7 天到期或令牌无效 | 登录已过期，请重新登录 |

只有持有过该会话刷新 cookie 的浏览器能看到这个区别；登录接口对错误密码、未知用户和停用账号仍然一律返回 `INVALID_CREDENTIALS`，不泄露账号是否存在。

## 限流

规则在 `zhiku.rate-limit`（登录按来源 IP 和用户名，刷新按 IP，问答 / 上传 / 评测 / 改密按账号），计数放在可替换的存储里：

| `ZHIKU_RATE_LIMIT_STORE` | 适用 | 说明 |
| --- | --- | --- |
| `memory`（默认） | 单实例、本地开发 | 进程内固定窗口，重启清零，多实例各算各的 |
| `postgres` | 多实例部署 | 表 `rate_limit_window`，一条 upsert 原子计数，时间以数据库 `now()` 为准；键只存 SHA-256；过期窗口定期清理 |

`postgres` 存储在数据库不可用时**放行并告警**（fail-open），不会因为限流把所有登录挡住；网关层限流仍应保留。没有做 Redis 实现：项目已经依赖 PostgreSQL，再引入 Redis 只为计数不划算；需要时实现 `RateLimitStore` 接口即可。

限流按 servlet 的 `remoteAddr` 取来源 IP，不信任客户端自带的 `X-Forwarded-For`。放在反向代理后面时（例如 compose 里的 nginx），设 `SERVER_FORWARD_HEADERS_STRATEGY=native`，并确保代理覆盖而不是追加 `X-Forwarded-For`；Tomcat 只信任内网地址发来的转发头。

## 企业 SSO（暂缓）

OIDC 登录这一轮没有做：它需要真实 IdP（发现文档、客户端注册、回调地址、用户与角色映射、首次登录建号策略）才能测出有意义的结果，用模拟 IdP 写出来的代码容易给人「已接入」的错觉。

预留的接入点：

1. 登录：新增 OIDC 回调控制器，校验 ID Token 后按 `sub`/邮箱找到或创建 `app_account`（`password_hash` 可存不可用的占位），然后调用与 `AccountSessions.login` 相同的发会话逻辑（建 `auth_session`、发 access token + 刷新 cookie）。之后的会话绑定、停用、降权、限流和审计全部沿用。
2. 角色：IdP 组到 `Role` 的映射放在配置里，每次登录同步；`JwtAccessScopeResolver` 不需要改，角色仍以数据库为准。
3. 管理端：登录页加「企业账号登录」按钮跳转授权地址；本地账号可以保留给应急管理员。
