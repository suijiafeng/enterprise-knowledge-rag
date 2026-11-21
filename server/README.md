# Server
前置 Java 21、Maven 3.9.11+、Docker（集成测试用 Testcontainers 启动 pgvector）。

```sh
mvn -B clean verify
```

## 本地运行
1. 仓库根目录复制 `.env.example` 为 `.env`，填入新的数据库密码和 `ZHIKU_JWT_SECRET`（`openssl rand -base64 48`）。
2. `docker compose up -d postgres`；首次启动时 Flyway 自动执行 `db/migration`（只做前向迁移，clean 已禁用）。
3. `cd server && SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run`（`dev` 配置写入演示账号 admin / operator / employee，并接受开发令牌）。`application.yml` 用 `spring.config.import: optional:file:../.env[.properties]` 读取仓库根目录的 `.env`（properties 格式，不要写 `export`）。默认端口 8080（`server.port`）。覆盖：`SERVER_PORT=8090 mvn spring-boot:run`，或 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8090`。管理端开发代理用 `ZHIKU_DEV_PROXY` 指向同一地址。
4. 可选：`docker compose exec -T postgres psql -U zhiku_local -d zhiku < scripts/dev-seed.sql` 写入虚构示例知识库与授权。
5. 命令行脚本用的开发令牌（仅 `dev` 配置有效）：`node scripts/dev-token.mjs --user 00000000-0000-4000-8000-0000000000a1 --enterprise 00000000-0000-4000-8000-0000000000e1 --roles KB_OPERATOR,EMPLOYEE`。

`/api/status`、`/api/health` 与 `/api/auth/*` 公开；其余 `/api/**` 需要 Bearer JWT，非 `/api` 路径一律拒绝。生产只接受 `/api/auth/login` 签发、绑定会话的 access token，每个请求核对会话与账号角色；授权库每请求从数据库读取。演示账号和开发令牌只在 `dev` 配置下存在，`prod` 配置（`SPRING_PROFILES_ACTIVE=prod`）下打开它们、缺少密钥或数据库密码都会拒绝启动。生产账号由管理员写入 `app_account`（bcrypt 哈希），没有默认用户或后门。

测试使用 Mockito subclass mock maker，避免容器/JDK 中 inline agent 自附着限制。测试 JWT 密钥每次运行随机生成，不入库。
