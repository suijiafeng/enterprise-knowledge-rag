import type { ReactNode } from "react";
import { NEEDS_KB, type NavKey, type PageContext } from "./nav";
import { NoKnowledgeBase } from "./NoKnowledgeBase";
import OverviewRoute from "./routes/overview";
import DocumentsRoute from "./routes/documents";
import QaRoute from "./routes/qa";
import RecordsRoute from "./routes/records";
import DebugRoute from "./routes/debug";
import EvaluationRoute from "./routes/evaluation";
import IntegrationRoute from "./routes/integration";
import UsersRoute from "./routes/users";
import SettingsRoute from "./routes/settings";

export { NoKnowledgeBase };

const ROUTES = {
  overview: OverviewRoute, documents: DocumentsRoute, qa: QaRoute, records: RecordsRoute, debug: DebugRoute,
  evaluation: EvaluationRoute, integration: IntegrationRoute, users: UsersRoute, settings: SettingsRoute,
} as const;

/** 同步版本（测试与服务端渲染用）；控制台外壳用 routes/index.ts 的懒加载版本。 */
export function pageFor(key: NavKey, ctx: PageContext = {}): ReactNode {
  if (ctx.client && !ctx.kbId && NEEDS_KB.includes(key)) return <NoKnowledgeBase />;
  const Route = ROUTES[key];
  return <Route {...ctx} />;
}
