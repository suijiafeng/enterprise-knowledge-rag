import type { PageContext } from "../nav";
import { UsersPage } from "../features/users/UsersPage";

export default function UsersRoute(ctx: PageContext) {
  return <UsersPage client={ctx.client} />;
}
