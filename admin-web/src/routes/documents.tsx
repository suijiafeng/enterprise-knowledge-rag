import { liveOf, type PageContext } from "../nav";
import { LiveDocuments } from "../features/documents/LiveDocuments";
import { DocumentsPage } from "../features/documents/DocumentsPage";
import { documentsFixture } from "../features/documents/fixtures";

export default function DocumentsRoute(ctx: PageContext) {
  const live = liveOf(ctx);
  return live ? <LiveDocuments {...live} status={ctx.status} /> : <DocumentsPage data={documentsFixture} />;
}
