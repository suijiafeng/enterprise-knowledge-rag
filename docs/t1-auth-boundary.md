# T1 身份与知识库授权边界 · 接口与测试计划
状态：2026-10-14 按 ADR-002 记录的推荐默认值实现：本地 HS256 JWT + 数据库授权表。生产 IdP、最终角色名称仍待业务确认。

## 已落地接口
- `auth.AccessScopeResolver`：应用层端口，返回当前请求的 `Optional<AccessScope>`。只允许由服务端认证上下文实现；无生产实现，容器中没有该 bean。
- `auth.AuthorizationDecision`：UNAUTHENTICATED / DENIED / PERMITTED。外企业与不存在的目标一律 DENIED，不可区分。
- `knowledgebase.KnowledgeBaseRef`：服务端持有的知识库身份（id + enterpriseId），不由客户端输入单独构造。
- `knowledgebase.KnowledgeBaseAuthorization`：所有知识库数据入口（查询、导出、历史、引用、原文读取）的统一门闸，参数为目标库与操作。参数里没有身份，身份只来自 resolver；resolver 返回 null 或空视为未认证。角色权限与知识库范围必须同时满足（PRD 第 3 章）。
- `auth.Role` / `auth.KnowledgeBaseAction` / `auth.RolePolicy`：按 PRD v0.3 第 3 章"建议映射"落地的角色矩阵，**待 D02 确认**。PRD 中"需显式内容管理/诊断授权"的格子尚无授权模型，一律拒绝；他人问答正文默认无人可读。
- HTTP：匿名访问业务路径 401 `UNAUTHENTICATED`，已认证无授权 403 `FORBIDDEN`，缺 CSRF 令牌 403。错误体固定为 `{code, message, requestId}` 并带 `X-Request-Id`，不含路径、主体或资源存在性。

## 已有测试（41 项通过）
- 单元：角色矩阵每格都有显式规则、员工不能管理/调试/读他人问答、显式授权格失败关闭、角色与库范围缺一即拒绝、无角色范围拒绝；无身份→未认证、resolver 返回 null 失败关闭、跨企业拒绝、同企业未授权库拒绝、外企业与未知库结果相同、空目标拒绝、构造参数非空。
- HTTP 冒烟：公开状态端点、匿名 GET/POST（含 CSRF）401、已认证 GET/POST 403、未知路径与业务路径响应一致。

## 已接入（2026-10-14）
- `SecurityConfiguration`：JWT 资源服务器，校验签名、iss、aud、必需 exp，以及会话绑定（`sid`）：会话必须未撤销、账号未停用。不绑定会话的令牌只在 `dev` 配置接受。密钥缺失或过短时 `StartupGuard` 拒绝启动。业务接口只认 bearer，关闭 CSRF；唯一的 cookie 是 `/api/auth` 下 HttpOnly + SameSite=Strict 的刷新令牌。不为管理端开放 CORS。
- `AccountSessions` / `AuthController`：用户名密码登录（bcrypt，未知用户也做同等代价比对），access token 15 分钟，刷新令牌 7 天、每次刷新轮换；旧令牌 30 秒后重放视为盗用并撤销整个会话。退出撤销会话，access token 和由它签发的 widget token 立即失效。
- 角色：绑定会话的令牌，角色取令牌与账号当前角色的交集，降权下一请求生效；`/api/admin/accounts` 供 IT 管理员新建账号、改角色、停用、重置密码（一次性临时密码）、踢下线（不能改自己）；`/api/auth/password` 本人改密。刷新失败区分 `ACCOUNT_DISABLED` / `SESSION_REVOKED` / `SESSION_EXPIRED`。详见 [security.md](security.md)。
- 限流（`RateLimitFilter` + `RateLimiter`，固定窗口）：登录按来源 IP 和用户名，刷新按 IP，问答、上传、评测、改密按用户；超限 429 `RATE_LIMITED` + `Retry-After`。计数默认在进程内存，多实例设 `ZHIKU_RATE_LIMIT_STORE=postgres` 共用一份；网关层限流仍建议保留。
- 安全响应头：CSP `default-src 'none'; frame-ancestors 'none'`、`X-Frame-Options: DENY`、`nosniff`、`Referrer-Policy: no-referrer`、`Permissions-Policy`、COOP；HTTPS 下 HSTS。
- `JwtAccessScopeResolver`：身份只取自已验证令牌；授权库每请求从 `kb_grant` 重读，撤权下一请求生效。
- 接口：`GET/POST /api/knowledge-bases`、`PUT/DELETE /api/knowledge-bases/{kb}/grants/{user}`（仅同企业 IT_ADMIN），授权变更写入追加式 `audit_event`。
- 集成测试（真实 pgvector）：无效签名/过期/错误 audience/缺 exp/非法 sub 一律 401；授权与撤权即时生效；请求体伪造 enterpriseId 被忽略；跨企业与不存在的库响应一致；审计不可更新。

## 待输入后补充的测试
1. 身份源接入：从真实认证上下文构造 `AccessScope` 的集成测试；伪造 `X-Enterprise-Id` 之类请求头或 DTO 字段必须被忽略。
2. 角色：D02 确认后按最终名称与职责修订矩阵；补显式内容管理授权与诊断授权模型及其测试；企业级操作（用户/角色/授权管理、模型凭据、审计查看）尚未建模。
3. 同受众拆库：同一企业两个库互不可见；一个用户被授予 A 不能读 B 的任何派生数据。
4. 撤权：授权删除后，检索、引用、原文、历史答案和继续对话在同一请求链内全部拒绝。
5. Widget：短期令牌签发、宿主来源允许列表、令牌过期后 401；不得使用长期 API Key。
6. CSRF/会话：根据选定方案（会话 Cookie 或 Bearer）决定 CSRF 策略并补对应测试。

## 未实现
登录、用户/角色存储、知识库持久化、任何业务路由。`KnowledgeBaseAuthorization` 尚无调用方。
