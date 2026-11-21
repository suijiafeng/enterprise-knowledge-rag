#!/usr/bin/env node
// 相关性门槛（ZHIKU_RETRIEVAL_MIN_SCORE，默认 0.45）的校准脚本：对一组「应能回答」和「应拒答」的问题取检索候选的
// 余弦相似度，在 0.10–0.90 之间扫描阈值，输出每个阈值下的召回与误放行，供人工选值。只做检索，不调用对话模型。
// LOCAL development only：使用 scripts/dev-token.mjs 签发的开发令牌（服务端需 SPRING_PROFILES_ACTIVE=dev）。
//
// 运行前把服务端的门槛临时关掉，否则低于门槛的候选在检索阶段就被丢弃、看不到分数：
//   ZHIKU_RETRIEVAL_MIN_SCORE=-1 ZHIKU_RETRIEVAL_MIN_LEXICAL=2 ZHIKU_EMBEDDING_PROVIDER=ollama ZHIKU_EMBEDDING_MODEL=bge-m3 ...
// 用法：
//   node scripts/calibrate-threshold.mjs                       # 新建知识库并写入 scripts/example-eval 的虚构文档
//   node scripts/calibrate-threshold.mjs --kb <id> --cases my-cases.json   # 用已有知识库和自己的问题集
//   [--base http://127.0.0.1:8080] [--k 10] [--out calibration.json]
// cases 文件格式：[{ "question": "...", "expect": "文档标题" }, { "question": "...", "expect": null }]，expect 为 null 表示应拒答。
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const args = Object.fromEntries(process.argv.slice(2).reduce((p, a, i, all) => (a.startsWith("--") ? [...p, [a.slice(2), all[i + 1]]] : p), []));
const BASE = args.base ?? "http://127.0.0.1:8080";
const K = Number(args.k ?? 10);
const USER = "00000000-0000-4000-8000-0000000000a1";
const ENTERPRISE = "00000000-0000-4000-8000-0000000000e1";
const token = execFileSync("node", [new URL("./dev-token.mjs", import.meta.url).pathname, "--user", USER, "--enterprise", ENTERPRISE,
  "--roles", "EMPLOYEE,KB_OPERATOR,IT_ADMIN", "--minutes", "60"]).toString().trim();
const doc = (f) => readFileSync(new URL(`./example-eval/${f}`, import.meta.url), "utf8");

/** 内置问题集：与 eval-baseline.mjs 同一批虚构文档。样本很少，只用来演示流程，不能代表真实业务问题。 */
const DEFAULT_CASES = [
  { question: "司龄满十年的员工年假几天？", expect: "年假办法（示例）" },
  { question: "年假申请要提前几天？", expect: "年假办法（示例）" },
  { question: "工作十年以上每年能休多少天假？", expect: "年假办法（示例）" },
  { question: "出差住宿超标怎么办？", expect: "差旅办法（示例）" },
  { question: "住酒店超过标准需要怎么处理？", expect: "差旅办法（示例）" },
  { question: "差旅报销须在出差结束后多少日内提交？", expect: "报销办法（示例）" },
  { question: "公司食堂几点开门？", expect: null },
  { question: "明天北京天气怎么样？", expect: null },
  { question: "怎么申请停车位？", expect: null },
  { question: "帮我写一首关于春天的诗", expect: null },
  { question: "公司股票今天涨了吗？", expect: null },
  { question: "笔记本电脑坏了找谁修？", expect: null },
];

async function api(method, path, body) {
  const init = { method, headers: { Authorization: `Bearer ${token}` } };
  if (body instanceof FormData) init.body = body;
  else if (body !== undefined) { init.headers["Content-Type"] = "application/json"; init.body = JSON.stringify(body); }
  const res = await fetch(BASE + path, init);
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function seed() {
  const kb = await api("POST", "/api/knowledge-bases", { name: `阈值校准-${Date.now()}` });
  await api("PUT", `/api/knowledge-bases/${kb.id}/grants/${USER}`);
  const docs = [["年假办法（示例）", "leave.md"], ["年假窗口说明（示例）", "trap.md"], ["差旅办法（示例）", "travel.md"], ["报销办法（示例）", "injection.md"]];
  for (const [title, file] of docs) {
    const d = await api("POST", `/api/knowledge-bases/${kb.id}/documents`, { title });
    const v = await api("POST", `/api/knowledge-bases/${kb.id}/documents/${d.id}/versions`, { versionLabel: "v1", effectiveFrom: "2025-01-01" });
    const form = new FormData();
    form.append("file", new Blob([doc(file)], { type: "text/markdown" }), file);
    await api("PUT", `/api/knowledge-bases/${kb.id}/documents/${d.id}/versions/${v.id}/content`, form);
    await api("POST", `/api/knowledge-bases/${kb.id}/documents/${d.id}/versions/${v.id}/publish`);
  }
  for (let i = 0; i < 240; i++) {
    const states = (await api("GET", `/api/knowledge-bases/${kb.id}/documents`)).flatMap((d) => d.versions ?? []).map((v) => v.indexStatus);
    if (states.length === docs.length && states.every((s) => s === "ready")) return kb.id;
    if (states.includes("failed")) throw new Error("indexing failed");
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("indexing did not finish in 2 minutes");
}

const kbId = args.kb ?? await seed();
const cases = args.cases ? JSON.parse(readFileSync(args.cases, "utf8")) : DEFAULT_CASES;
const vector = (c) => c.vectorScore ?? c.score;
const rows = [];
let model = "";
for (const c of cases) {
  const trace = await api("POST", `/api/knowledge-bases/${kbId}/retrieval-debug`, { query: c.question, k: K });
  model = trace.embeddingModel;
  const best = Math.max(-1, ...trace.candidates.map(vector));
  const relevant = c.expect ? Math.max(-1, ...trace.candidates.filter((x) => x.title === c.expect).map(vector)) : null;
  rows.push({ question: c.question, expect: c.expect, candidates: trace.candidates.length, best: Number(best.toFixed(4)),
    relevant: relevant === null ? null : Number(relevant.toFixed(4)), top: trace.candidates[0]?.title ?? null });
}

const answerable = rows.filter((r) => r.expect);
const refuse = rows.filter((r) => !r.expect);
if (refuse.length && refuse.every((r) => r.candidates === 0)) {
  console.warn("警告：应拒答的问题一个候选都没有，服务端的相关性门槛可能仍然开着（需 ZHIKU_RETRIEVAL_MIN_SCORE=-1），结果不可用于校准。");
}
const sweep = [];
for (let t = 0.1; t <= 0.9001; t += 0.05) {
  const recall = answerable.filter((r) => r.relevant !== null && r.relevant >= t).length;
  const falseAccept = refuse.filter((r) => r.best >= t).length;
  sweep.push({ threshold: Number(t.toFixed(2)), recall: `${recall}/${answerable.length}`, falseAccept: `${falseAccept}/${refuse.length}`,
    score: (answerable.length ? recall / answerable.length : 0) - (refuse.length ? falseAccept / refuse.length : 0) });
}
const top = Math.max(...sweep.map((s) => s.score));
const candidates = sweep.filter((s) => s.score === top).map((s) => s.threshold);

console.log(`embedding model: ${model} · 知识库 ${kbId} · ${answerable.length} 个应答问题 / ${refuse.length} 个应拒答问题 · top-${K}`);
console.table(rows.map(({ question, expect, best, relevant, top: first }) => ({ question, expect: expect ?? "（应拒答）", relevant, best, top: first })));
console.table(sweep.map(({ score, ...s }) => s));
console.log(`召回 − 误放行 最高的阈值：${candidates.join(" / ")}。`);
console.log("这只是本问题集和当前模型下的参考，样本很少；请用真实业务问题集复核，并结合词覆盖门槛（ZHIKU_RETRIEVAL_MIN_LEXICAL）一起看，再改 ZHIKU_RETRIEVAL_MIN_SCORE。");
if (args.out) writeFileSync(args.out, JSON.stringify({ base: BASE, kbId, model, k: K, rows, sweep, candidates }, null, 2));
