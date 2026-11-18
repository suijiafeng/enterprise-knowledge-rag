import { Alert, Button, Card, Checkbox, Col, InputNumber, Row, Select, Space, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type ApiClient, type EvalCase, type EvalResult, type EvalRun, type EvalSettings } from "../../api/client";
import { EmptyState, ErrorState } from "../../components/StateViews";
import { metricCards } from "./LiveEvaluation";
import { DEFINITIONS, barValue, decimal, highlightChunk, isLowSample, latencyRows, metricRows, parameterRows, ratioText } from "./evalPanel";

const JUDGE: Record<string, string> = { llm: "LLM 评审", simulated: "模拟评审（词面）" };
const CITATION: Record<string, string> = { ok: "引用编号可核对", none: "没有引用编号", "out-of-range": "引用编号越界" };

function MetricBars({ run }: { run: EvalRun }) {
  const items = [
    { key: "hit" as const, label: "Hit@K", text: ratioText(run.summary.hit.num, run.summary.hit.den) },
    { key: "recall" as const, label: "Recall@K", text: ratioText(run.summary.recall.num, run.summary.recall.den) },
    { key: "mrr" as const, label: "MRR", text: decimal(run.summary.mrr) },
    { key: "faithfulness" as const, label: "忠实度", text: decimal(run.summary.faithfulness) },
    { key: "relevance" as const, label: "相关性", text: decimal(run.summary.relevance) },
  ];
  return (
    <div className="metric-bars">
      {items.map((item) => {
        const value = barValue(run.summary, item.key);
        return (
          <div key={item.key} className="metric-bar-row">
            <span>{item.label}</span>
            <div className="metric-bar-track" role="img" aria-label={`${item.label} ${item.text}`}>
              {value == null ? <span className="metric-bar-empty">无数据</span> : <div className="metric-bar-fill" style={{ width: `${Math.round(value * 100)}%` }} />}
            </div>
            <span className="mono">{item.text}</span>
          </div>
        );
      })}
    </div>
  );
}

function LatencyChart({ results }: { results: EvalResult[] }) {
  const rows = latencyRows(results);
  if (rows.length === 0) return <EmptyState icon="chart" title="没有可绘制的延迟" description="计入分母的用例才会出现在这里。" />;
  const max = Math.max(1, ...rows.map((row) => row.latencyMs));
  return (
    <div className="bar-chart" aria-label="各题检索延迟">
      {rows.map((row) => (
        <div key={row.caseId} className="bar-column" role="img" aria-label={`${row.question} ${row.latencyMs} 毫秒`}>
          <span className="bar-value">{row.latencyMs}</span>
          <div className="bar bar-latest" style={{ height: `${Math.max(4, Math.round((row.latencyMs / max) * 100))}%` }} />
          <span className="bar-label">{row.question.slice(0, 8)}</span>
        </div>
      ))}
    </div>
  );
}

function ChunkText({ text, chunkStart, versionId, spans }: { text: string; chunkStart: number; versionId: string; spans: { versionId: string; startOffset: number; endOffset: number }[] }) {
  const parts = highlightChunk(text, chunkStart, versionId, spans);
  return <div className="eval-chunk">{parts.map((part, index) => part.hit ? <mark key={index} className="eval-hit">{part.text}</mark> : <span key={index}>{part.text}</span>)}</div>;
}

export function EvalPanel({ client, kbId, cases, refreshKey }: { client: ApiClient; kbId: string; cases: EvalCase[]; refreshKey: number }) {
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [settings, setSettings] = useState<EvalSettings>();
  const [selectedId, setSelectedId] = useState<string>();
  const [compareId, setCompareId] = useState<string>();
  const [detailId, setDetailId] = useState<string>();
  const [onlyLow, setOnlyLow] = useState(false);
  const [threshold, setThreshold] = useState(0.6);
  const [k, setK] = useState(5);
  const [contextChunks, setContextChunks] = useState<number | null>(null);
  const [generate, setGenerate] = useState(false);
  const [rerank, setRerank] = useState(false);
  const [rerankPool, setRerankPool] = useState<number | null>(null);
  const [judge, setJudge] = useState<"" | "llm" | "simulated">("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError>();

  const load = useCallback(async () => {
    try {
      const [listed, current] = await Promise.all([client.evalRuns(kbId), client.evalSettings(kbId)]);
      setRuns(listed);
      setSettings(current);
      setSelectedId((id) => id && listed.some((run) => run.id === id) ? id : listed[0]?.id);
      setCompareId((id) => id && listed.some((run) => run.id === id) ? id : listed[1]?.id);
      setError(undefined);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : new ApiError(0, "NETWORK", "请求失败"));
    }
  }, [client, kbId]);

  useEffect(() => { void load(); }, [load, refreshKey]);

  const selected = runs.find((run) => run.id === selectedId);
  const compared = runs.find((run) => run.id === compareId);
  const visible = selected ? (onlyLow ? selected.results.filter((result) => isLowSample(result, threshold)) : selected.results) : [];
  const detail = visible.find((result) => result.caseId === detailId) ?? visible[0];
  const evidence = cases.find((item) => item.id === detail?.caseId)?.evidence ?? [];

  const execute = async () => {
    setBusy(true);
    setError(undefined);
    try {
      const run = await client.runEvaluation(kbId, {
        k,
        ...(contextChunks ? { contextChunks } : {}),
        ...(generate ? { generate: true } : {}),
        ...(judge ? { judge } : {}),
        ...(rerank ? { rerank: true, ...(rerankPool ? { rerankPool } : {}) } : {}),
      });
      setSelectedId(run.id);
      await load();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : new ApiError(0, "NETWORK", "请求失败"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {error && <div className="scaffold-alert"><ErrorState code={error.code} message={error.message} requestId={error.requestId} /></div>}
      <Card size="small" className="side-card" title="运行参数">
        <div className="eval-toolbar">
          <InputNumber min={1} max={settings?.maxK ?? 30} value={k} onChange={(value) => setK(value ?? 1)} addonBefore="召回 K" aria-label="召回 K" />
          <InputNumber min={1} max={settings?.maxContextChunks ?? 10} value={contextChunks} onChange={(value) => setContextChunks(typeof value === "number" ? value : null)}
            addonBefore="入模切块" placeholder={settings ? `默认 ${Math.min(settings.defaultContextChunks, k)}` : "默认"} aria-label="入模切块" />
          <Checkbox checked={rerank} onChange={(event) => setRerank(event.target.checked)}>重排</Checkbox>
          <InputNumber min={k} max={settings?.maxK ?? 30} value={rerankPool} disabled={!rerank}
            onChange={(value) => setRerankPool(typeof value === "number" ? value : null)}
            addonBefore="重排前" placeholder={rerank ? `默认 ${Math.min(settings?.maxK ?? 30, Math.max(k, k * 4))}` : "关闭"} aria-label="重排前条数" />
          <Checkbox checked={generate} onChange={(event) => setGenerate(event.target.checked)}>同时生成回答</Checkbox>
          <Select value={judge} onChange={setJudge} disabled={!generate} aria-label="评审方式" style={{ minWidth: 180 }}
            options={[{ value: "", label: "有模型则用 LLM 评审" }, { value: "llm", label: "LLM 评审" }, { value: "simulated", label: "模拟评审（词面）" }]} />
          <Button type="primary" loading={busy} onClick={() => void execute()}>运行评测</Button>
        </div>
        <div className="card-unit">
          切块 {settings ? `${settings.chunkStrategy} ${settings.chunkMaxChars}/${settings.chunkOverlap}` : "读取中"} · 重排 {settings?.rerank ?? "字符二元组 BM25 与向量分加权"}
          {settings ? ` · 对话模型 ${settings.chatConfigured ? settings.chatModel : "未配置"}` : ""}
        </div>
      </Card>

      {!selected && <EmptyState icon="chart" title="尚无评测运行" description="批准用例后运行评测。图表只显示已经保存的运行，没有运行时不画指标。" />}

      {selected && (
        <>
          <Typography.Text type="secondary" className="page-sub">
            运行 {selected.id.slice(0, 8)} · {new Date(selected.createdAt).toLocaleString("zh-CN")} · K {selected.k}
            {selected.generate ? ` · 生成 ${JUDGE[selected.judge ?? ""] ?? selected.judge ?? "未评审"}` : " · 仅检索"}
            {selected.summary.indexChangedDuringRun ? " · 运行期间索引发生过变化" : ""}
          </Typography.Text>
          <Row gutter={[16, 16]}>
            {metricCards(selected.summary, selected.k).map((card) => (
              <Col key={card.title} xs={24} sm={12} xl={8}>
                <Card className="metric-card">
                  <div className="metric-title">{card.title}</div>
                  <div className="metric-value">{card.value}</div>
                  <div className="metric-delta down">{card.sub}</div>
                </Card>
              </Col>
            ))}
            <Col xs={24} xl={12}>
              <Card size="small" title="本次运行的指标">
                <MetricBars run={selected} />
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card size="small" title="各题检索延迟（毫秒）">
                <LatencyChart results={selected.results} />
              </Card>
            </Col>
          </Row>
        </>
      )}

      <Card size="small" className="side-card" title="运行列表">
        {runs.length === 0 ? <EmptyState icon="chart" title="还没有运行" description="每次运行单独保存，可在下面选择两次对比。" /> : (
          <Table<EvalRun> size="small" rowKey="id" pagination={false} dataSource={runs} scroll={{ x: 860 }}
            rowClassName={(run) => run.id === selectedId ? "row-selected" : ""}
            onRow={(run) => ({ onClick: () => setSelectedId(run.id) })}
            columns={[
              { title: "时间", dataIndex: "createdAt", render: (value: string) => new Date(value).toLocaleString("zh-CN") },
              { title: "K", dataIndex: "k", className: "mono" },
              { title: "入模", dataIndex: "contextChunks", className: "mono", render: (value: number | null) => value ?? "—" },
              { title: "重排", key: "rerank", render: (_, run) => run.rerankEnabled ? `前 ${run.rerankPool ?? "—"}` : "关闭" },
              { title: "评审", dataIndex: "judge", render: (value: string | null, run) => run.generate ? (JUDGE[value ?? ""] ?? value ?? "—") : "未生成" },
              { title: "Hit", key: "hit", className: "mono", render: (_, run) => `${run.summary.hit.num}/${run.summary.hit.den}` },
              { title: "Recall", key: "recall", className: "mono", render: (_, run) => `${run.summary.recall.num}/${run.summary.recall.den}` },
              { title: "MRR", key: "mrr", className: "mono", render: (_, run) => decimal(run.summary.mrr) },
              { title: "忠实度", key: "faith", className: "mono", render: (_, run) => decimal(run.summary.faithfulness) },
              { title: "P95", key: "p95", className: "mono", render: (_, run) => run.summary.p95Ms == null ? "—" : `${run.summary.p95Ms}` },
            ]} />
        )}
      </Card>

      <Card size="small" className="side-card" title="两次运行对比">
        {runs.length < 2 || !selected || !compared || selected.id === compared.id ? (
          <EmptyState icon="chart" title="需要两次不同的运行" description="选择左右两次已经保存的运行。没有第二组结果时，这里不补数字。" />
        ) : (
          <>
            <Space wrap className="eval-toolbar">
              <Select aria-label="对比左侧运行" value={selected.id} onChange={setSelectedId} style={{ minWidth: 220 }}
                options={runs.map((run) => ({ value: run.id, label: `K=${run.k} · ${new Date(run.createdAt).toLocaleString("zh-CN")}` }))} />
              <Select aria-label="对比右侧运行" value={compared.id} onChange={setCompareId} style={{ minWidth: 220 }}
                options={runs.map((run) => ({ value: run.id, label: `K=${run.k} · ${new Date(run.createdAt).toLocaleString("zh-CN")}` }))} />
            </Space>
            <Table size="small" pagination={false} rowKey="key" dataSource={[...parameterRows(selected, compared), ...metricRows(selected, compared)]}
              columns={[
                { title: "项目", dataIndex: "name" },
                { title: "左侧", dataIndex: "left", render: (value: string, row) => row.changed ? <Tag>{value}</Tag> : value },
                { title: "右侧", dataIndex: "right", render: (value: string, row) => row.changed ? <Tag color="blue">{value}</Tag> : value },
                { title: "右减左", dataIndex: "delta", className: "mono", render: (value: string) => value || "—" },
              ]} />
          </>
        )}
      </Card>

      {selected && (
        <Card size="small" title="逐条明细">
          <div className="eval-toolbar">
            <Checkbox checked={onlyLow} onChange={(event) => setOnlyLow(event.target.checked)}>只看低分或失败</Checkbox>
            <InputNumber min={0} max={1} step={0.1} value={threshold} onChange={(value) => setThreshold(value ?? 0.6)} addonBefore="分数低于" aria-label="低分阈值" />
          </div>
          {visible.length === 0 ? <EmptyState icon="chart" title="没有符合筛选的样本" description="放宽阈值，或取消“只看低分或失败”。" /> : (
            <Table<EvalResult> size="small" rowKey="caseId" pagination={false} dataSource={visible} scroll={{ x: 760 }}
              rowClassName={(row) => row.caseId === detail?.caseId ? "row-selected" : ""}
              onRow={(row) => ({ onClick: () => setDetailId(row.caseId) })}
              columns={[
                { title: "问题", dataIndex: "question" },
                { title: "证据", key: "evidence", className: "mono", render: (_, row) => row.mappingFailed || row.sourceUnavailable ? "—" : `${row.evidenceHit}/${row.evidenceTotal}` },
                { title: "名次", dataIndex: "firstRelevantRank", className: "mono", render: (value: number | null) => value ?? "—" },
                { title: "忠实度", dataIndex: "faithfulness", className: "mono", render: (value: number | null) => decimal(value) },
                { title: "相关性", dataIndex: "relevance", className: "mono", render: (value: number | null) => decimal(value) },
                { title: "失败原因", dataIndex: "failure", render: (value: string | null) => value || "—" },
              ]} />
          )}
          {detail && (
            <div className="cause-detail">
              <div>{detail.question}</div>
              <div className="card-unit">
                检索 {detail.latencyMs} ms
                {detail.generationMs == null ? " · 未生成" : ` · 生成 ${detail.generationMs} ms`}
                {` · prompt token ${detail.promptTokens ?? "不可得"} · completion token ${detail.completionTokens ?? "不可得"}`}
                {detail.citation ? ` · ${CITATION[detail.citation] ?? detail.citation}` : ""}
              </div>
              {detail.failure && <div>失败原因：{detail.failure}</div>}
              {detail.judgeReason && <div className="card-unit">评审说明：{detail.judgeReason}</div>}
              {detail.answer && <div>回答：{detail.answer}</div>}
              {evidence.map((item, index) => (
                <div key={index} className="card-unit">期望片段：{item.documentTitle} {item.versionLabel} [{item.startOffset}, {item.endOffset}) {item.quote ? `「${item.quote}」` : "摘录已隐藏"}</div>
              ))}
              {(detail.retrieved ?? []).map((hit) => (
                <div key={hit.chunkId}>
                  <div className="card-unit">#{hit.rank} {hit.documentTitle} {hit.versionLabel} · {selected.rerankEnabled ? "融合分" : "向量分"} {hit.score.toFixed(3)} · {hit.overlapsEvidence ? "与期望片段相交" : "未相交"}</div>
                  <ChunkText text={hit.text} chunkStart={hit.startOffset} versionId={hit.versionId} spans={evidence} />
                </div>
              ))}
            </div>
          )}
        </Card>
      )}
      <Card size="small" className="side-card" title="指标定义">
        <ul className="eval-definitions">{DEFINITIONS.map((line) => <li key={line}>{line}</li>)}</ul>
      </Card>
    </>
  );
}
