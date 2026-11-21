import { defineConfig, devices } from "@playwright/test";

/**
 * 端到端用例跑在一套已经启动的本地环境上（dev 配置 + 内置 mock 模型 + 演示账号），不自己起服务：
 *   docker compose up -d --build        → E2E_BASE_URL=http://127.0.0.1:5180（默认）
 *   或 server（dev）+ admin-web 的 npm run dev → E2E_BASE_URL=http://127.0.0.1:5173
 * 用例会新建文档和账号（名字带时间戳，虚构内容），不清理；请只对本地演示库运行。
 */
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  // 用例共用演示账号，登录限流按来源 IP 计数：串行执行更稳定。
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://127.0.0.1:5180",
    locale: "zh-CN",
    timezoneId: "Asia/Shanghai",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } }],
});
