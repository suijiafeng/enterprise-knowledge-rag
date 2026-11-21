import { resolve } from "node:path";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// 开发代理让 API 调用保持同源，因此服务端无需为管理控制台开启 CORS。
// ZHIKU_DEV_PROXY 可写在仓库根 .env、admin-web/.env，或在启动前导出。默认指向本机 8080。
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
  };
});
