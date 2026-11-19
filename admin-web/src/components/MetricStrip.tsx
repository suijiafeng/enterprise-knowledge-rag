export interface MetricItem {
  title: string;
  value: string;
  note?: string;
  trend?: "up" | "flat" | "bad";
}

/** 一行指标，用分割线而不是并排卡片。窄屏收成两列。 */
export function MetricStrip({ items }: { items: MetricItem[] }) {
  return (
    <section className="metric-strip" aria-label="指标">
      {items.map((item) => (
        <div key={item.title} className="metric-cell">
          <div className="metric-title">{item.title}</div>
          <div className="metric-value">{item.value}</div>
          {item.note ? <div className={`metric-delta ${item.trend ?? "flat"}`}>{item.note}</div> : null}
        </div>
      ))}
    </section>
  );
}
