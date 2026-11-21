import { liveOf, type PageContext } from "../nav";
import { LiveQa } from "../features/qa/LiveQa";
import { QaWorkbench } from "../features/qa/QaWorkbench";
import { qaFixture } from "../features/qa/fixtures";

export default function QaRoute(ctx: PageContext) {
  const live = liveOf(ctx);
  return live ? <LiveQa {...live} modelEnabled={ctx.status?.modelEnabled ?? false} /> : <QaWorkbench data={qaFixture} />;
}
