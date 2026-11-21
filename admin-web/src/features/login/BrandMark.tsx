/** 「知」字星座：矢 + 口 的笔画连成星座线，端点是星。坐标在 0–100 的方框里。 */
const STROKES: ReadonlyArray<ReadonlyArray<readonly [number, number]>> = [
  [[21, 8], [12, 27]],
  [[14, 25], [47, 25]],
  [[5, 47], [53, 47]],
  [[30, 9], [30, 47], [9, 89]],
  [[32, 56], [51, 86]],
  [[62, 31], [62, 80]],
  [[62, 31], [93, 31], [93, 77]],
  [[62, 75], [93, 75]],
];

const POINTS = STROKES.flat().filter((p, i, all) => all.findIndex((q) => Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) <= 4) === i);

export function BrandMark() {
  return (
    <svg className="zk-mark" viewBox="-4 -4 108 108" aria-hidden="true" focusable="false">
      <g className="zk-mark-lines">
        {STROKES.map((s, i) => (
          <path key={i} d={"M" + s.map(([x, y]) => `${x} ${y}`).join(" L")} pathLength={1} style={{ animationDelay: `${(0.15 + i * 0.16).toFixed(2)}s` }} />
        ))}
      </g>
      <g className="zk-mark-stars">
        {POINTS.map(([x, y], i) => <circle key={i} cx={x} cy={y} r={2.1} style={{ animationDelay: `${(1.4 + i * 0.09).toFixed(2)}s` }} />)}
      </g>
    </svg>
  );
}
