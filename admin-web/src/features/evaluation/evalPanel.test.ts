import { describe, expect, it } from "vitest";
import type { EvalResult, EvalRun } from "../../api/client";
import { highlightChunk, isLowSample, metricRows, parameterRows } from "./evalPanel";

const result = (patch: Partial<EvalResult>): EvalResult => ({
  caseId: "c", question: "年假几天", label: "answerable", mappingFailed: false, hit: true, evidenceHit: 1, evidenceTotal: 1, latencyMs: 10, ...patch,
});

const run = (patch: Partial<EvalRun> = {}): EvalRun => ({
  id: "r", k: 5, model: "test-hash-64", createdAt: "2026-11-17T00:00:00Z", chatModel: "test-scripted", generate: true, judge: "simulated",
  contextChunks: 5, chunkMaxChars: 800, chunkOverlap: 100, rerankEnabled: false,
  summary: { cases: 2, evaluated: 2, prepFailed: 0, notApplicable: 0, hit: { num: 1, den: 2 }, recall: { num: 1, den: 2 }, recallMacro: 0.5, p95Ms: 40, p50Ms: 10, mrr: 0.5, faithfulness: 0.3, relevance: 0.6, p50E2eMs: 20, p95E2eMs: 50, judged: 2 },
  results: [result({})],
  ...patch,
});

describe("evaluation panel calculations", () => {
  it("highlights only the overlapping slice of the same version", () => {
    const parts = highlightChunk("甲乙丙丁", 10, "v", [
      { versionId: "other", startOffset: 10, endOffset: 14 },
      { versionId: "v", startOffset: 11, endOffset: 13 },
      { versionId: "v", startOffset: 13, endOffset: 13 },
    ]);
    expect(parts).toEqual([{ text: "甲", hit: false }, { text: "乙丙", hit: true }, { text: "丁", hit: false }]);
    expect(highlightChunk("甲乙", 0, "v", [{ versionId: "v", startOffset: 2, endOffset: 4 }])).toEqual([{ text: "甲乙", hit: false }]);
  });

  it("treats misses and low scores as low samples but not an unjudged hit", () => {
    expect(isLowSample(result({ hit: false }), 0.6)).toBe(true);
    expect(isLowSample(result({ faithfulness: 0.59 }), 0.6)).toBe(true);
    expect(isLowSample(result({ relevance: 0.6 }), 0.6)).toBe(false);
    expect(isLowSample(result({ failure: "生成失败" }), 0.6)).toBe(true);
    expect(isLowSample(result({ mappingFailed: true }), 0.6)).toBe(true);
    expect(isLowSample(result({ faithfulness: null, relevance: null }), 0.6)).toBe(false);
  });

  it("compares stored parameters and leaves a delta blank when a metric was not measured", () => {
    const left = run();
    const right = run({ id: "r2", k: 1, contextChunks: 1, summary: { ...left.summary, hit: { num: 2, den: 2 }, mrr: null, faithfulness: null } });
    expect(parameterRows(left, right).find((row) => row.key === "k")).toMatchObject({ left: "5", right: "1", changed: true });
    expect(parameterRows(left, right).find((row) => row.key === "rerank")?.right).toBe("关闭");
    const mrr = metricRows(left, right).find((row) => row.key === "mrr");
    expect(mrr?.delta).toBe("");
    expect(mrr?.right).toBe("—");
    const hit = metricRows(left, right).find((row) => row.key === "hit");
    expect(hit?.left).toContain("1/2");
    expect(hit?.delta).toBe("+0.500");
    const missing = metricRows(left, right).find((row) => row.key === "faith");
    expect(missing?.right).toBe("—");
  });
});