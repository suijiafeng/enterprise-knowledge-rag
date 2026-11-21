import { describe, expect, it } from "vitest";
import { traceFromResult } from "./liveTrace";
import type { TraceResult } from "../../api/client";

const result: TraceResult = {
  traceId: "t1", question: "年假?", at: "2026-10-14T00:00:00Z", embeddingModel: "ollama:bge-m3", dimension: 1024,
  stages: [
    { key: "rewrite", name: "查询改写", status: "skipped", elapsedMs: null, summary: "未改写" },
    { key: "vector", name: "向量召回", status: "success", elapsedMs: 12, summary: "精确检索" },
    { key: "lexical", name: "词法召回", status: "skipped", elapsedMs: null, summary: "未启用" },
    { key: "fusion", name: "融合排序", status: "skipped", elapsedMs: null, summary: "未启用" },
    { key: "rerank", name: "重排", status: "skipped", elapsedMs: null, summary: "未接入" },
  ],
  candidates: [{ chunkId: "c", documentId: "d", title: "手册", versionId: "v", versionLabel: "v3", seq: 2, heading: null,
    startOffset: 0, endOffset: 10, text: "年假…", score: 0.91, rank: 1 }],
};

describe("traceFromResult", () => {
  it("keeps only real scores and appends a skipped generation stage", () => {
    const trace = traceFromResult(result, "制度库", "授权版本 实时");
    expect(trace.stages.map((s) => s.key)).toEqual(["rewrite", "vector", "lexical", "fusion", "rerank", "generation"]);
    expect(trace.stages.at(-1)?.status).toBe("skipped");
    expect(trace.candidates[0]).toMatchObject({ vector: 0.91, lexical: null, fused: null, rerank: null, finalRank: 1, usage: "none", chunk: "#002" });
    expect(trace.params.find((p) => p.label.startsWith("Embedding"))?.value).toBe("ollama:bge-m3 · 1024 维");
  });

  it("shows every stage score the hybrid pipeline reports", () => {
    const hybrid: TraceResult = {
      ...result,
      stages: [...result.stages.slice(0, 4), { key: "relevance", name: "相关性门槛", status: "success", elapsedMs: 0, summary: "保留 1 / 3 条" },
        { key: "rerank", name: "重排", status: "success", elapsedMs: 1, summary: "BM25 + 向量" }],
      candidates: [{ ...result.candidates[0]!, score: 0.7, vectorScore: 0.42, lexicalScore: 0.75, fusedScore: 0.032, rerankScore: 0.7 }],
    };
    const trace = traceFromResult(hybrid, "制度库", "授权版本 实时");
    expect(trace.stages.map((s) => s.key)).toContain("relevance");
    expect(trace.candidates[0]).toMatchObject({ vector: 0.42, lexical: 0.75, fused: 0.032, rerank: 0.7 });
    expect(trace.params.find((p) => p.label === "相关性门槛")?.value).toBe("保留 1 / 3 条");
  });
});
