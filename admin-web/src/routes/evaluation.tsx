import { liveOf, type PageContext } from "../nav";
import { LiveEvaluation } from "../features/evaluation/LiveEvaluation";
import { PublicEvaluation } from "../features/evaluation/PublicEvaluation";
import { exampleSnapshot } from "../features/evaluation/exampleSnapshot";

export default function EvaluationRoute(ctx: PageContext) {
  const live = liveOf(ctx);
  return live ? <LiveEvaluation {...live} /> : <PublicEvaluation snapshot={exampleSnapshot} />;
}
