import "./backdrop.css";

/**
 * 登录页的动态背景：极光、星点、两侧的「制度文件星座」和偶尔的流星。纯 CSS 动画，
 * 整层 aria-hidden + pointer-events:none；系统开启「减少动态效果」时全部定格。按需懒加载，不进首屏主包。
 */

/** 固定种子的伪随机数，保证每次渲染的星空一致（也便于截图对比）。 */
function seeded(seed: number) {
  let t = seed;
  return () => {
    t = (t + 0x6d2b79f5) | 0;
    let r = Math.imul(t ^ (t >>> 15), 1 | t);
    r = (r + Math.imul(r ^ (r >>> 7), 61 | r)) ^ r;
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
}

const rand = seeded(7);
const SIZES = [0.6, 0.7, 0.8, 0.9, 1.1, 1.3, 1.6];
const STARS = Array.from({ length: 110 }, () => ({
  x: Math.round(rand() * 1440), y: Math.round(rand() * 900), r: SIZES[Math.floor(rand() * SIZES.length)] ?? 1,
  delay: (rand() * 6).toFixed(1), duration: (3.5 + rand() * 4).toFixed(1),
}));

/** 节点是制度文件，连线是引用关系；名称均为虚构示例。 */
const NODES = {
  a: [170, 170, "员工手册"], b: [318, 292, "年休假管理办法"], c: [150, 420, "考勤制度"], d: [282, 612, "信息安全规范"], e: [128, 760, ""],
  f: [1256, 150, "差旅费管理办法"], g: [1330, 330, "报销制度"], h: [1150, 470, "采购管理办法"], i: [1302, 650, "保密协议"], j: [1176, 790, ""],
} as const satisfies Record<string, readonly [number, number, string]>;
type NodeKey = keyof typeof NODES;
const EDGES: ReadonlyArray<readonly [NodeKey, NodeKey]> = [["a", "b"], ["b", "c"], ["a", "c"], ["c", "d"], ["d", "e"], ["f", "g"], ["g", "h"], ["f", "h"], ["h", "i"], ["i", "j"]];

export default function LoginBackdrop() {
  return (
    <div className="zk-backdrop" aria-hidden="true">
      <div className="zk-aurora"><i className="a1" /><i className="a2" /><i className="a3" /></div>
      <svg className="zk-stars" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" focusable="false">
        {STARS.map((s, i) => <circle key={i} cx={s.x} cy={s.y} r={s.r} style={{ animationDelay: `-${s.delay}s`, animationDuration: `${s.duration}s` }} />)}
      </svg>
      <svg className="zk-const" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" focusable="false">
        {EDGES.map(([u, v], k) => {
          const [x1, y1] = NODES[u];
          const [x2, y2] = NODES[v];
          return (
            <g key={k}>
              <line className="ce" x1={x1} y1={y1} x2={x2} y2={y2} />
              <line className="cf" x1={x1} y1={y1} x2={x2} y2={y2} pathLength={1} style={{ animationDelay: `${(k * 1.1).toFixed(1)}s` }} />
            </g>
          );
        })}
        {Object.values(NODES).map(([x, y, label], k) => (
          <g key={k}>
            <g className="cn" style={{ animationDelay: `${(k * 0.7).toFixed(1)}s` }}>
              <circle className="halo" cx={x} cy={y} r={9} style={{ animationDelay: `${(k * 0.7).toFixed(1)}s` }} />
              <circle cx={x} cy={y} r={2.6} />
            </g>
            {label && <text x={x < 720 ? x + 12 : x - 12} y={y + 4} textAnchor={x < 720 ? "start" : "end"}>{label}</text>}
          </g>
        ))}
      </svg>
      <div className="zk-meteor" />
    </div>
  );
}
