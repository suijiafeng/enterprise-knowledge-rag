import type { EvalResult, EvalRun, EvalSummary } from "../../api/client";

/** 与服务端 EvalMetrics / AnswerJudges 同一套定义，面板和 README 都引用这段。 */
export const DEFINITIONS = [
  "命中率 Hit@K：计入分母的用例中，至少一条期望片段被本次 top-K 切块无间隙完整覆盖的比例。来源失效、无法映射到当前切块的用例不进分母。",
  "Recall@K：被完整覆盖的期望片段数 / 期望片段总数。宏平均是每题覆盖率的平均。",
  "MRR：每题取 top-K 里第一块与任一期望片段相交的名次的倒数，没有相交记 0，再取平均。相交只认同一内容版本上的半开区间，而且不等于完整覆盖，所以 MRR 可以高于命中率。",
  "忠实度衡量回答里的结论有多少被送入模型的资料支持；相关性衡量回答有多针对问题并覆盖必要事实。分数是 0 到 1。LLM 评审只接受 JSON；模拟评审只检查必要事实是否原样出现，不是语义判断。没有评出的分数保持为空，不写成 0。",
  "检索 P50/P95 只统计计入分母的检索耗时。端到端是检索加生成，没有生成耗时的用例不进该分母。百分位用最近秩、向上取整。",
  "切块窗口记录的是部署配置。重排是候选池内的字符二元组 BM25 与向量分加权，可设置送入重排的条数，保留条数就是召回 K。没有词法命中时顺序不变。Token 只有模型返回用量时才显示。",
];

export function ratioText(num: number, den: number) {
  return den ? `${num}/${den}（${((num / den) * 100).toFixed(1)}%）` : `${num}/${den}`;
}

export function decimal(value: number | null | undefined) {
  return value == null || Number.isNaN(value) ? "—" : value.toFixed(3);
}

export function isLowSample(result: EvalResult, threshold: number) {
  if (result.mappingFailed || result.sourceUnavailable || !result.hit || (result.failure != null && result.failure !== "")) return true;
  if (typeof result.faithfulness === "number" && result.faithfulness < threshold) return true;
  if (typeof result.relevance === "number" && result.relevance < threshold) return true;
  return false;
}

export interface TextPart {
  text: string;
  hit: boolean;
}

/** 用期望片段的字符区间在切块正文里标出相交部分。切块正文按下标 0 对应 chunkStart。 */
export function highlightChunk(text: string, chunkStart: number, versionId: string, spans: { versionId: string; startOffset: number; endOffset: number }[]): TextPart[] {
  const ranges: [number, number][] = [];
  for (const span of spans) {
    if (span.versionId !== versionId) continue;
    const start = Math.max(span.startOffset, chunkStart) - chunkStart;
    const end = Math.min(span.endOffset, chunkStart + text.length) - chunkStart;
    if (start < end) ranges.push([start, end]);
  }
  ranges.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const merged: [number, number][] = [];
  for (const range of ranges) {
    const last = merged[merged.length - 1];
    if (!last || range[0] > last[1]) merged.push([range[0], range[1]]);
    else last[1] = Math.max(last[1], range[1]);
  }
  const parts: TextPart[] = [];
  let cursor = 0;
  for (const [start, end] of merged) {
    if (cursor < start) parts.push({ text: text.slice(cursor, start), hit: false });
    parts.push({ text: text.slice(start, end), hit: true });
    cursor = end;
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor), hit: false });
  return parts.filter((part) => part.text.length > 0);
}

export interface CompareRow {
  key: string;
  name: string;
  left: string;
  right: string;
  changed: boolean;
  delta: string;
}

function pair(key: string, name: string, left: string, right: string, delta = ""): CompareRow {
  return { key, name, left, right, changed: left !== right, delta };
}

export function parameterRows(left: EvalRun, right: EvalRun): CompareRow[] {
  const chunk = (run: EvalRun) => run.chunkMaxChars == null ? "—" : `fixed-overlap ${run.chunkMaxChars}/${run.chunkOverlap ?? "—"}`;
  return [
    pair("k", "召回 K", String(left.k), String(right.k)),
    pair("context", "入模切块", left.contextChunks == null ? "—" : String(left.contextChunks), right.contextChunks == null ? "—" : String(right.contextChunks)),
    pair("chunk", "切块窗口 / 重叠", chunk(left), chunk(right)),
    pair("rerank", "重排", left.rerankEnabled ? `开启 · 前 ${left.rerankPool ?? "—"} 条` : "关闭", right.rerankEnabled ? `开启 · 前 ${right.rerankPool ?? "—"} 条` : "关闭"),
    pair("generate", "生成回答", left.generate ? "是" : "否", right.generate ? "是" : "否"),
    pair("judge", "评审", left.judge ?? "—", right.judge ?? "—"),
    pair("embed", "向量模型", left.model ?? "—", right.model ?? "—"),
    pair("chat", "对话模型", left.chatModel ?? "—", right.chatModel ?? "—"),
  ];
}

function numDelta(left: number | null | undefined, right: number | null | undefined) {
  if (left == null || right == null) return "";
  const delta = right - left;
  const sign = delta > 0 ? "+" : "";
  return `${sign}${delta.toFixed(3)}`;
}

export function metricRows(left: EvalRun, right: EvalRun): CompareRow[] {
  const summary = (run: EvalRun) => run.summary;
  const ratio = (key: string, name: string, pick: (s: EvalSummary) => { num: number; den: number }) => {
    const a = pick(summary(left));
    const b = pick(summary(right));
    const delta = a.den && b.den ? numDelta(a.num / a.den, b.num / b.den) : "";
    return pair(key, name, ratioText(a.num, a.den), ratioText(b.num, b.den), delta);
  };
  const score = (key: string, name: string, pick: (s: EvalSummary) => number | null | undefined) => {
    const a = pick(summary(left));
    const b = pick(summary(right));
    return pair(key, name, decimal(a), decimal(b), numDelta(a, b));
  };
  return [
    ratio("hit", "Hit@K", (s) => s.hit),
    ratio("recall", "Recall@K", (s) => s.recall),
    score("macro", "宏平均 Recall", (s) => s.recallMacro),
    score("mrr", "MRR", (s) => s.mrr),
    score("faith", "忠实度", (s) => s.faithfulness),
    score("rel", "相关性", (s) => s.relevance),
    score("p50", "检索 P50 ms", (s) => s.p50Ms),
    score("p95", "检索 P95 ms", (s) => s.p95Ms),
    score("e50", "端到端 P50 ms", (s) => s.p50E2eMs),
    score("e95", "端到端 P95 ms", (s) => s.p95E2eMs),
  ];
}

export function barValue(summary: EvalSummary | undefined, key: "hit" | "recall" | "mrr" | "faithfulness" | "relevance"): number | null {
  if (!summary) return null;
  if (key === "hit" || key === "recall") {
    const ratio = summary[key];
    return ratio.den ? ratio.num / ratio.den : null;
  }
  const value = summary[key];
  return value == null ? null : value;
}

export function latencyRows(results: EvalResult[]) {
  return results.filter((result) => !result.mappingFailed && !result.sourceUnavailable);
}
