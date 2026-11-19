import { ColumnChart } from "../../components/ColumnChart";
import type { OverviewData } from "./fixtures";

/** 近 7 日问答量。最后一天用主色，其余为中性灰。 */
export function DailyQuestionsChart({ data }: { data: OverviewData["dailyQuestions"] }) {
  const last = data.length - 1;
  return (
    <ColumnChart
      label="近 7 日问答量"
      points={data.map((point, index) => ({
        key: point.day,
        label: point.day,
        value: point.count,
        detail: `${point.day} 问答 ${point.count} 次`,
        tone: index === last ? "ink" : "neutral",
      }))}
    />
  );
}
