import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { evaluationFixture, type EvalRow } from "./fixtures";
import { p95, resultOf, summarize } from "./metrics";
import { PublicEvaluation, type ExampleSnapshot } from "./PublicEvaluation";
import type { EvalRun } from "../../api/client";

const rows = evaluationFixture.rows;
const sampleRun = {
  id: "example-run", k: 1, model: "test-hash-64", createdAt: "2026-11-17T00:00:00Z", rerankEnabled: false,
  summary: { cases: 1, evaluated: 1, prepFailed: 0, notApplicable: 0, hit: { num: 1, den: 2 }, recall: { num: 1, den: 2 }, recallMacro: 0.5, p95Ms: 8, mrr: 0.5, faithfulness: null, relevance: null },
  results: [],
} as EvalRun;
const snapshot: ExampleSnapshot = { example: true, notice: "虚构示例，不是企业数据", runs: [sampleRun] };
const html = renderToStaticMarkup(<PublicEvaluation snapshot={snapshot} />);

const row = (patch: Partial<EvalRow>): EvalRow => ({ ...rows[0]!, ...patch });

describe("evaluation metrics", () => {
  it("passes only with evidence hit, correct human verdict and supported citations", () => {
    expect(resultOf(row({}))).toBe("pass");
    expect(resultOf(row({ evidenceHit: 0 }))).toBe("fail");
    expect(resultOf(row({ verdict: "incorrect" }))).toBe("fail");
    expect(resultOf(row({ citationSupported: false }))).toBe("fail");
    expect(resultOf(row({ verdict: "pending" }))).toBe("pending");
    expect(resultOf(row({ mappingFailed: true, evidenceHit: 0 }))).toBe("prep-failed");
  });

  it("excludes preparation failures from every denominator", () => {
    const s = summarize(rows);
    const evaluated = rows.filter((r) => !r.mappingFailed);
    expect(s.evaluated).toBe(evaluated.length);
    expect(s.prepFailed).toBe(rows.length - evaluated.length);
    expect(s.hit.den).toBe(evaluated.length);
    expect(s.hit.num).toBe(evaluated.filter((r) => r.evidenceHit > 0).length);
    expect(s.correct.num).toBe(evaluated.filter((r) => r.verdict === "correct").length);
  });

  it("computes nearest-rank P95", () => {
    expect(p95([100])).toBe(100);
    expect(p95([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])).toBe(19);
    expect(p95([])).toBeNull();
  });
});

describe("public evaluation page", () => {
  it("shows the example hit ratio and no private actions", () => {
    expect(html).toContain("1/2");
    expect(html).toContain("示例");
    expect(html).toContain("虚构示例，不是企业数据");
    expect(html).not.toContain("运行评测");
    expect(html).not.toContain("导入 JSON");
    expect(html).not.toContain("确认入库");
  });
});
