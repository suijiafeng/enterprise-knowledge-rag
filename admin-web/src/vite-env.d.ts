/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 生产构建默认不显示演示账号；只有显式设为 "true" 的构建（如本地预览）才会在服务端返回 demoLogin 时显示。 */
  readonly VITE_ZHIKU_DEMO_LOGIN?: string;
}
