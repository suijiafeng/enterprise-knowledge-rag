import { useState } from "react";

export interface ColumnPoint {
  key: string;
  label: string;
  value: number;
  detail: string;
  tone?: "ink" | "alert" | "neutral";
}

const FILL = { ink: "#2f5bd3", alert: "#d4380d", neutral: "#c3d3f2" };

/** 带水平参考线和悬浮说明的柱图。高度由数据决定，不写死。 */
export function ColumnChart({ label, points }: { label: string; points: ColumnPoint[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const width = 640;
  const height = 196;
  const padL = 40;
  const padR = 8;
  const padT = 12;
  const padB = 28;
  const innerW = width - padL - padR;
  const innerH = height - padT - padB;
  const max = Math.max(1, ...points.map((point) => point.value));
  const slot = points.length ? innerW / points.length : innerW;
  const barW = Math.max(3, Math.min(28, slot * 0.52));
  const ticks = 4;

  return (
    <div className="chart">
      <svg viewBox={`0 0 ${width} ${height}`} className="chart-svg" aria-label={label}>
        {Array.from({ length: ticks + 1 }, (_, index) => {
          const ratio = index / ticks;
          const y = padT + innerH - ratio * innerH;
          return (
            <g key={index}>
              <line x1={padL} x2={width - padR} y1={y} y2={y} stroke="#e6e9ef" strokeDasharray="3 3" />
              <text x={padL - 6} y={y + 3} textAnchor="end" className="chart-tick">{Math.round(max * ratio)}</text>
            </g>
          );
        })}
        {points.map((point, index) => {
          const barH = (point.value / max) * innerH;
          const x = padL + slot * index + (slot - barW) / 2;
          const y = padT + innerH - barH;
          return (
            <g key={point.key} role="img" aria-label={point.detail}
              onMouseEnter={() => setHover(index)} onMouseLeave={() => setHover(null)}>
              <title>{point.detail}</title>
              <rect x={x} y={y} width={barW} height={Math.max(barH, point.value > 0 ? 1 : 0)} rx="2" fill={FILL[point.tone ?? "neutral"]} />
              {points.length <= 12 && (
                <text x={padL + slot * index + slot / 2} y={height - 8} textAnchor="middle" className="chart-tick">{point.label}</text>
              )}
            </g>
          );
        })}
      </svg>
      {hover != null && points[hover] && (
        <div className="chart-tip" style={{ left: `${((padL + slot * hover + slot / 2) / width) * 100}%` }} role="tooltip">
          {points[hover].detail}
        </div>
      )}
    </div>
  );
}

export function RatioChart({ label, items }: {
  label: string;
  items: { key: string; label: string; value: number | null; text: string }[];
}) {
  const [hover, setHover] = useState<number | null>(null);
  const width = 560;
  const rowH = 32;
  const padL = 84;
  const padR = 88;
  const padT = 6;
  const height = padT + items.length * rowH + 18;
  const innerW = width - padL - padR;
  return (
    <div className="chart">
      <svg viewBox={`0 0 ${width} ${height}`} className="chart-svg" aria-label={label}>
        {[0, 0.5, 1].map((guide) => {
          const x = padL + guide * innerW;
          return (
            <g key={guide}>
              <line x1={x} x2={x} y1={padT} y2={padT + items.length * rowH} stroke="#e6e9ef" strokeDasharray="3 3" />
              <text x={x} y={height - 2} textAnchor="middle" className="chart-tick">{guide}</text>
            </g>
          );
        })}
        {items.map((item, index) => {
          const y = padT + index * rowH;
          const barW = item.value == null ? 0 : Math.max(0, Math.min(1, item.value)) * innerW;
          return (
            <g key={item.key} role="img" aria-label={`${item.label} ${item.text}`}
              onMouseEnter={() => setHover(index)} onMouseLeave={() => setHover(null)}>
              <title>{`${item.label} ${item.text}`}</title>
              <text x={padL - 8} y={y + 16} textAnchor="end" className="chart-tick">{item.label}</text>
              {item.value == null
                ? <text x={padL + 4} y={y + 16} className="chart-tick">无数据</text>
                : <rect x={padL} y={y + 8} width={barW} height={8} rx="2" fill="#2f5bd3" />}
              <text x={width - 8} y={y + 16} textAnchor="end" className="chart-tick">{item.text}</text>
            </g>
          );
        })}
      </svg>
      {hover != null && items[hover] && (
        <div className="chart-tip" style={{ left: "50%" }} role="tooltip">{items[hover].label} {items[hover].text}</div>
      )}
    </div>
  );
}
