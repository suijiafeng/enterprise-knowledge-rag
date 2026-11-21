import type { PageContext } from "../nav";
import { liveOf } from "../nav";
import { LiveOverview } from "../features/overview/LiveOverview";
import { OverviewPage } from "../features/overview/OverviewPage";
import { overviewFixture } from "../features/overview/fixtures";

export default function OverviewRoute(ctx: PageContext) {
  const live = liveOf(ctx);
  const links = { onOpenDocuments: () => ctx.navigate?.("documents"), onOpenRecords: () => ctx.navigate?.("records") };
  return live
    ? <LiveOverview {...live} zone={ctx.status?.businessTimeZone ?? "Asia/Shanghai"} onNewQuestion={() => ctx.navigate?.("qa")} {...links} />
    : <OverviewPage data={overviewFixture} {...links} />;
}
