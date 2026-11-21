import type { ApiClient, Status } from "./api/client";

type RoleName = "IT_ADMIN" | "KB_OPERATOR" | "EMPLOYEE";
const ALL: readonly RoleName[] = ["IT_ADMIN", "KB_OPERATOR", "EMPLOYEE"];

/**
 * 菜单项与可见角色。只是精简菜单、减少无权限的空页面；真正的权限仍由服务端每次请求判定。
 * 示例数据模式（未登录）显示全部菜单。
 */
export const NAV = [
  { key: "overview", label: "知识库总览", roles: ALL },
  { key: "documents", label: "文档管理", roles: ["KB_OPERATOR"] },
  { key: "qa", label: "问答工作台", roles: ALL },
  { key: "records", label: "问答记录", roles: ALL },
  { key: "debug", label: "检索调试", roles: ["KB_OPERATOR"] },
  { key: "evaluation", label: "评测中心", roles: ["KB_OPERATOR"] },
  { key: "integration", label: "API 与集成", roles: ["KB_OPERATOR", "IT_ADMIN"] },
  { key: "users", label: "用户与权限", roles: ["IT_ADMIN"] },
  { key: "settings", label: "系统设置", roles: ["KB_OPERATOR", "IT_ADMIN"] },
] as const satisfies readonly { key: string; label: string; roles: readonly RoleName[] }[];

/** 当前账号可见的菜单；roles 为 undefined（示例数据模式）时全部可见。 */
export function visibleNav(roles: readonly string[] | undefined) {
  if (!roles) return [...NAV];
  return NAV.filter((n) => (n.roles as readonly string[]).some((r) => roles.includes(r)));
}

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
