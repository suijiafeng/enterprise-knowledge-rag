import type { PageContext } from "../nav";
import { RecordsPage } from "../features/records/RecordsPage";

export default function RecordsRoute(ctx: PageContext) {
  return <RecordsPage client={ctx.client} kbId={ctx.kbId} />;
}
