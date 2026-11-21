/// <reference types="vitest/config" />
import { resolve } from "node:path";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// 开发代理让 API 调用保持同源，因此服务端无需为管理控制台开启 CORS。
// ZHIKU_DEV_PROXY 可写在仓库根 .env、admin-web/.env，或在启动前导出。默认指向本机 8080。
export const SECURITY_HEADERS: Record<string, string> = {
  "Content-Security-Policy":
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=()",
};

export default defineConfig(({ mode }) => {
  const local = loadEnv(mode, process.cwd(), "");
  const root = loadEnv(mode, resolve(process.cwd(), ".."), "");
  const target = process.env.ZHIKU_DEV_PROXY || local.ZHIKU_DEV_PROXY || root.ZHIKU_DEV_PROXY || "http://127.0.0.1:8080";
  return {
    plugins: [react()],
    // 页面按路由懒加载（src/routes）；React 单独成块便于缓存，antd 组件随用到它的页面拆分。
    build: {
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              { name: "react", test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/, priority: 20 },
            ],
          },
        },
      },
    },
    server: { proxy: { "/api": target } },
    // 构建产物的预览服务器带上与生产静态托管相同的安全响应头（生产由网关 / 静态服务器下发同样的头）。
    // antd 的 CSS-in-JS 需要内联样式；脚本只允许同源。
    preview: { proxy: { "/api": target }, headers: SECURITY_HEADERS },
    test: {
      // happy-dom 在每个测试文件结束时销毁 window；React 若还有排队的 passive effect，会在销毁之后读取 window.event
      // 并抛出 “window is not defined”。这发生在全部断言结束之后，与被测行为无关；只忽略这一种，其余未处理错误照常让测试失败。
      onUnhandledError: (error) => !(error.name === "ReferenceError" && error.message === "window is not defined"
        && String(error.stack ?? "").includes("react-dom")),
    },
  };
});
