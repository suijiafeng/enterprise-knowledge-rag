import { liveOf, type PageContext } from "../nav";
import { LiveDebug } from "../features/debug/LiveDebug";
import { RetrievalDebugPage } from "../features/debug/RetrievalDebugPage";
import { debugFixture } from "../features/debug/fixtures";

export default function DebugRoute(ctx: PageContext) {
  const live = liveOf(ctx);
  return live ? <LiveDebug {...live} kbName={ctx.kbName ?? ""} /> : <RetrievalDebugPage trace={debugFixture} />;
}
