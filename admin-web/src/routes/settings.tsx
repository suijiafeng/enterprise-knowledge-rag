import { liveOf, type PageContext } from "../nav";
import { SettingsPage } from "../features/settings/SettingsPage";

export default function SettingsRoute(ctx: PageContext) {
  const live = liveOf(ctx);
  return <SettingsPage status={ctx.status} error={ctx.statusError} {...(live ? { live } : {})} />;
}
