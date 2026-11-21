import type { TraceResult } from "../../api/client";
import type { DebugTrace, Stage, StageKey } from "./fixtures";

const KEYS: readonly StageKey[] = ["rewrite", "vector", "lexical", "fusion", "relevance", "rerank", "generation"];
const isKey = (k: string): k is StageKey => (KEYS as readonly string[]).includes(k);

function summaryOf(result: TraceResult, key: string, fallback: string): string {
  return result.stages.find((s) => s.key === key)?.summary ?? fallback;
}

/** 将服务端 trace 映射为页面模型。只显示服务端实际给出的分数；其余保持 null。 */
export function traceFromResult(result: TraceResult, knowledgeBase: string, scopeVersion: string): DebugTrace {
  const stages: Stage[] = result.stages
    .filter((s) => isKey(s.key))
    .map((s) => ({ key: s.key as StageKey, name: s.name, status: s.status, elapsedMs: s.elapsedMs, summary: s.summary }));
  stages.push({ key: "generation", name: "上下文与生成", status: "skipped", elapsedMs: null, summary: "调试不调用模型" });
  return {
    traceId: result.traceId,
    question: result.question,
    knowledgeBase,
    scopeVersion,
    configVersion: result.configVersion === undefined ? "当前服务配置" : result.configVersion === 0 ? "部署默认值" : `配置版本 ${result.configVersion}`,
    stages,
    candidates: result.candidates.map((c) => ({
      chunk: `#${String(c.seq).padStart(3, "0")}`,
      document: c.title,
      version: c.versionLabel,
      vector: c.vectorScore ?? c.score,
      lexical: c.lexicalScore ?? null,
      fused: c.fusedScore ?? null,
      rerank: c.rerankScore ?? null,
      finalRank: c.rank,
      usage: "none" as const,
    })),
    params: [
      { label: "检索方式", value: summaryOf(result, "vector", "授权范围内向量检索") },
      { label: "Embedding 模型 / 维度", value: `${result.embeddingModel} · ${result.dimension} 维` },
      { label: "词法召回", value: summaryOf(result, "lexical", "未启用") },
      { label: "融合", value: summaryOf(result, "fusion", "未启用") },
      { label: "重排", value: summaryOf(result, "rerank", "未开启") },
      { label: "相关性门槛", value: summaryOf(result, "relevance", "未启用") },
      { label: "查询时点", value: result.at },
    ],
  };
}
