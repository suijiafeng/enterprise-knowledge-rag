import type { PageContext } from "../nav";
import { IntegrationPage } from "../features/integration/IntegrationPage";

export default function IntegrationRoute(ctx: PageContext) {
  return <IntegrationPage kbId={ctx.kbId} kbName={ctx.kbName} />;
}
