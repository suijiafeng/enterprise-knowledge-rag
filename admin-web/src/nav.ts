import type { ApiClient, Status } from "./api/client";

export const NAV = [
  { key: "overview", label: "知识库总览" },
  { key: "documents", label: "文档管理" },
  { key: "qa", label: "问答工作台" },
  { key: "records", label: "问答记录" },
  { key: "debug", label: "检索调试" },
  { key: "evaluation", label: "评测中心" },
  { key: "integration", label: "API 与集成" },
  { key: "users", label: "用户与权限" },
  { key: "settings", label: "系统设置" },
] as const;

export type NavKey = (typeof NAV)[number]["key"];

export interface PageContext {
  client?: ApiClient | undefined;
  kbId?: string | undefined;
  kbName?: string | undefined;
  status?: Status | undefined;
  statusError?: string | undefined;
  navigate?: ((key: NavKey) => void) | undefined;
}

/** 实时页面需要已连接的会话并选中知识库；否则显示示例页面并加以标注。 */
export const liveOf = (ctx: PageContext) => (ctx.client && ctx.kbId ? { client: ctx.client, kbId: ctx.kbId } : undefined);

/** 已登录但没有选中知识库（无授权库或列表未返回）时，这些页面显示空态，不退回示例数据。 */
export const NEEDS_KB: readonly NavKey[] = ["overview", "documents", "qa", "debug", "evaluation"];
