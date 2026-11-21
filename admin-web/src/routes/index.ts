import { lazy, type ComponentType } from "react";
import type { NavKey, PageContext } from "../nav";

/** 按路由拆包：每个页面单独一个 chunk，首屏只加载外壳和当前页面。 */
export const LAZY_ROUTES: Record<NavKey, ComponentType<PageContext>> = {
  overview: lazy(() => import("./overview")),
  documents: lazy(() => import("./documents")),
  qa: lazy(() => import("./qa")),
  records: lazy(() => import("./records")),
  debug: lazy(() => import("./debug")),
  evaluation: lazy(() => import("./evaluation")),
  integration: lazy(() => import("./integration")),
  users: lazy(() => import("./users")),
  settings: lazy(() => import("./settings")),
};
