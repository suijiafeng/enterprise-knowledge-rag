import { Alert, Button, Card, Checkbox, Col, Input, InputNumber, Row, Select, Space, Tag, Typography } from "antd";
import { useCallback, useEffect, useState, useRef } from "react";
import { createRequestGate } from "../../api/requestGate";
import { ApiError, type ApiClient, type DocumentInfo, type EvalCase, type EvalResult, type EvalSummary, type EvidenceInput, type CaseDetails } from "../../api/client";
import { EmptyState, ErrorState, LoadingList } from "../../components/StateViews";
import { EvalPanel } from "./EvalPanel";

const LABEL: Record<EvalCase["label"], string> = { answerable: "可回答", clarify: "应澄清", handoff: "应转人工" };
const pct = (n: number, d: number) => (d ? ` · ${((n / d) * 100).toFixed(1)}%` : "");

/** 只显示某次运行实际测得的指标。没有测到的分数是破折号，不补成 0，也不显示人工准确率。 */
export function metricCards(summary: EvalSummary | undefined, k: number) {
  if (!summary) {
    return [`Hit@${k}（片段完整覆盖）`, `Recall@${k}（片段）`, "MRR", "检索 P50 / P95", "忠实度 / 相关性", "未计入分母"]
      .map((title) => ({ title, value: "—", sub: "尚未运行" }));
  }
  const retrieval = summary.p50Ms == null && summary.p95Ms == null ? "—" : `${summary.p50Ms ?? "—"} / ${summary.p95Ms ?? "—"} ms`;
  const judged = summary.faithfulness == null && summary.relevance == null
    ? "—"
    : `${summary.faithfulness == null ? "—" : summary.faithfulness.toFixed(3)} / ${summary.relevance == null ? "—" : summary.relevance.toFixed(3)}`;
  return [
    { title: `Hit@${k}（片段完整覆盖）`, value: `${summary.hit.num}/${summary.hit.den}`, sub: pct(summary.hit.num, summary.hit.den).slice(3) || "—" },
    { title: `Recall@${k}（片段）`, value: `${summary.recall.num}/${summary.recall.den}`,
      sub: summary.recallMacro == null ? "—" : `宏平均 ${(summary.recallMacro * 100).toFixed(1)}%` },
    { title: "MRR", value: summary.mrr == null ? "—" : summary.mrr.toFixed(3), sub: "第一处相交切块的倒数名次" },
    { title: "检索 P50 / P95", value: retrieval, sub: summary.p50E2eMs == null && summary.p95E2eMs == null ? "未测端到端延迟" : `端到端 ${summary.p50E2eMs ?? "—"} / ${summary.p95E2eMs ?? "—"} ms` },
    { title: "忠实度 / 相关性", value: judged, sub: summary.judged ? `已评审 ${summary.judged} 题` : "本次未评审" },
    { title: "未计入分母", value: String(summary.prepFailed + (summary.sourceUnavailable ?? 0)),
      sub: `准备失败 ${summary.prepFailed}（无法映射到当前索引）· 来源失效 ${summary.sourceUnavailable ?? 0}（已撤回、删除或不在有效期）` },
  ];
}

export function resultTag(r: EvalResult) {
  if (r.sourceUnavailable) return "来源已失效";
  if (r.mappingFailed) return "评测准备失败";
  return r.hit ? "命中" : "未命中";
}

export function evidenceEntry(versionId: string, quote: string, occurrence: number | undefined): EvidenceInput {
  return occurrence ? { versionId, quote: quote.trim(), occurrence } : { versionId, quote: quote.trim() };
}

/** 可回答用例批准前还缺的项（服务端同样校验）。 */
export function missingForApproval(c: EvalCase): string[] {
  if (c.label !== "answerable") return [];
  return [
    ...(c.evidence.length === 0 ? ["证据"] : []),
    ...(c.expectedAnswer ? [] : ["参考答案"]),
    ...(c.requiredFacts.length === 0 ? ["必要事实"] : []),
  ];
}

/** 把服务端的 ISO 时刻转成 datetime-local 的本地值，回填后再提交不改变时刻。 */
export function localDateTimeValue(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function runWarnings(summary: EvalSummary | undefined): string[] {
  return summary?.indexChangedDuringRun ? ["运行期间索引发生变化，结果可能混用新旧切块，请重新运行后再比较。"] : [];
}

export function parseFacts(text: string) {
  return text.split("\n").map((f) => f.trim()).filter(Boolean);
}

export interface DetailsInput {
  expectedAnswer: string;
  facts: string;
  appliesTo: string;
  /** datetime-local 输入值，按浏览器本地时间解释。 */
  queryAt: string;
}

export function detailsBody(v: DetailsInput): CaseDetails {
  return {
    ...(v.expectedAnswer.trim() ? { expectedAnswer: v.expectedAnswer.trim() } : {}),
    requiredFacts: parseFacts(v.facts),
    ...(v.appliesTo.trim() ? { appliesTo: v.appliesTo.trim() } : {}),
    ...(v.queryAt ? { queryAt: new Date(v.queryAt).toISOString() } : {}),
  };
}

export function CaseList({ cases, onApprove, onEdit }: { cases: EvalCase[]; onApprove: (c: EvalCase) => void; onEdit: (c: EvalCase) => void }) {
  if (cases.length === 0) return <EmptyState icon="chart" title="暂无评测用例" description="新建用例，或在问答记录中把点踩加入评测候选。" />;
  return (
    <ul className="session-list">
      {cases.map((c) => {
        const missing = c.status === "candidate" ? missingForApproval(c) : [];
        // 样本字段加入之前批准的用例已冻结，只做说明不提示补齐。
        const legacy = c.sourceAvailable && c.status === "approved" && c.label === "answerable" && (!c.expectedAnswer || c.requiredFacts.length === 0);
        return (
          <li key={c.id} className="eval-case">
            <div>
              <Tag color={c.status === "approved" ? "green" : "orange"}>{c.status === "approved" ? "已批准" : "候选"}</Tag>
              {c.source.startsWith("example:") && <Tag color="gold">示例</Tag>}
              <Tag>{LABEL[c.label]}</Tag>
              {missing.length > 0 && <Tag color="red">待补齐：{missing.join("、")}</Tag>}
              {legacy && <Tag>批准时未要求参考答案与必要事实</Tag>}
              <span>{c.question}</span>
            </div>
            {!c.sourceAvailable && <div className="card-unit">来源已撤回或删除，参考答案与必要事实不再显示</div>}
            {c.expectedAnswer && <div className="card-unit">参考答案：{c.expectedAnswer}</div>}
            {c.requiredFacts.length > 0 && <div className="card-unit">必要事实：{c.requiredFacts.join("；")}</div>}
            {(c.appliesTo || c.queryAt) && (
              <div className="card-unit">
                {c.appliesTo && `适用：${c.appliesTo}`}{c.appliesTo && c.queryAt && " · "}
                {c.queryAt && `查询时点：${new Date(c.queryAt).toLocaleString("zh-CN")}`}
              </div>
            )}
            {c.evidence.map((e, i) => (
              <div key={i} className="card-unit">
                证据：{e.documentTitle} {e.versionLabel} · {e.available ? `「${e.quote ?? ""}」` : "来源已撤回或删除，摘录不再显示"} [{e.startOffset}, {e.endOffset})
              </div>
            ))}
            {c.status === "candidate" && (
              <Space size={4}>
                <Button size="small" onClick={() => onEdit(c)}>补齐</Button>
                <Button size="small" type="primary" disabled={missing.length > 0} onClick={() => onApprove(c)}>批准</Button>
              </Space>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function EvidenceForm({ docs, initial, onSubmit, onCancel, submitLabel }: {
  docs: DocumentInfo[];
  initial?: EvalCase;
  submitLabel: string;
  onSubmit: (v: { question: string; label: EvalCase["label"]; evidence: EvidenceInput[]; details: CaseDetails }) => Promise<void>;
  onCancel?: () => void;
}) {
  const initialQuestion = initial?.question;
  const [details, setDetails] = useState<DetailsInput>({
    expectedAnswer: initial?.expectedAnswer ?? "",
    facts: (initial?.requiredFacts ?? []).join("\n"),
    appliesTo: initial?.appliesTo ?? "",
    queryAt: localDateTimeValue(initial?.queryAt ?? null),
  });
  const [question, setQuestion] = useState(initialQuestion ?? "");
  const [label, setLabel] = useState<EvalCase["label"]>(initial?.label ?? "answerable");
  type Row = { key: number; versionId: string; quote: string; occurrence?: number; startOffset?: number };
  const [evidence, setEvidence] = useState<Row[]>(() => (initial?.evidence ?? []).map((e, key) => ({
    key, versionId: e.versionId, quote: e.quote ?? "", startOffset: e.startOffset,
  })));
  const nextKey = useRef(evidence.length);
  const pending = useRef(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string>();
  const update = (key: number, change: Partial<Row>) => setEvidence((rows) => rows.map((row) => {
    if (row.key !== key) return row;
    // 编辑内容或选址后不复用旧位置；未改的重复摘录保留服务端已确认起点。
    const { startOffset: _offset, ...rest } = row;
    return { ...rest, ...change };
  }));
  const invalid = evidence.some((e) => !e.versionId || e.quote.trim().length < 2);
  const submit = async () => {
    if (pending.current) return;
    pending.current = true;
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({ question: question.trim(), label, evidence: evidence.map((e) => e.startOffset === undefined
        ? evidenceEntry(e.versionId, e.quote, e.occurrence)
        : { versionId: e.versionId, quote: e.quote.trim(), startOffset: e.startOffset }), details: detailsBody(details) });
    } catch {
      setFormError("保存失败，请核对输入后重试");
    } finally {
      pending.current = false;
      setSubmitting(false);
    }
  };
  const versions = docs.flatMap((d) => d.versions.map((v) => ({ value: v.id, label: `${d.title} ${v.versionLabel}` })));
  return (
    <div className="eval-form">
      {initialQuestion === undefined && (
        <>
          <Input placeholder="问题" value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={2000} aria-label="评测问题" />
          <Select<EvalCase["label"]> value={label} onChange={setLabel} aria-label="标签"
            options={(Object.keys(LABEL) as EvalCase["label"][]).map((k) => ({ value: k, label: LABEL[k] }))} />
        </>
      )}
      {evidence.map((e, i) => (
        <div key={e.key} className="eval-form">
          <Typography.Text>证据 {i + 1}</Typography.Text>
          <Select<string> placeholder="证据所在文档版本" options={versions} disabled={submitting}
            onChange={(v) => update(e.key, { versionId: v })} aria-label={`证据版本 ${i + 1}`} value={e.versionId || null} />
          <Input.TextArea rows={2} maxLength={2000} disabled={submitting} placeholder="粘贴原文摘录" value={e.quote}
            onChange={(event) => update(e.key, { quote: event.target.value })} aria-label={`原文摘录 ${i + 1}`} />
          <InputNumber min={1} precision={0} disabled={submitting} placeholder="重复摘录选第几处；未改条目保留原位置" value={e.occurrence ?? null}
            onChange={(v) => {
              if (v !== null) update(e.key, { occurrence: v });
              else setEvidence((rows) => rows.map((row) => {
                if (row.key !== e.key) return row;
                const { occurrence: _occurrence, startOffset: _offset, ...rest } = row;
                return rest;
              }));
            }} aria-label={`第几处 ${i + 1}`} />
          <Button size="small" disabled={submitting} aria-label={`删除证据 ${i + 1}`}
            onClick={() => setEvidence((rows) => rows.filter((row) => row.key !== e.key))}>删除此证据</Button>
        </div>
      ))}
      <Button size="small" disabled={submitting || evidence.length >= 10} onClick={() => {
        const key = nextKey.current++;
        setEvidence((rows) => [...rows, { key, versionId: "", quote: "" }]);
      }}>新增证据</Button>
      <Typography.Text type="secondary">最多 10 条；保存会更新完整证据列表，删除条目须明确操作。</Typography.Text>
      {formError && <Alert type="error" title={formError} />}
      <Input.TextArea rows={2} maxLength={2000} placeholder="参考答案" value={details.expectedAnswer} aria-label="参考答案"
        onChange={(e) => setDetails({ ...details, expectedAnswer: e.target.value })} />
      <Input.TextArea rows={2} placeholder="必要事实，每行一条（最多 10 条）" value={details.facts} aria-label="必要事实"
        onChange={(e) => setDetails({ ...details, facts: e.target.value })} />
      <Input placeholder="适用对象（可空），如：正式员工" maxLength={200} value={details.appliesTo} aria-label="适用对象"
        onChange={(e) => setDetails({ ...details, appliesTo: e.target.value })} />
      <Input type="datetime-local" value={details.queryAt} aria-label="查询时点（可空，默认运行时刻）"
        onChange={(e) => setDetails({ ...details, queryAt: e.target.value })} />
      <Space>
        <Button type="primary" size="small" loading={submitting}
          disabled={submitting || !question.trim() || invalid || (!initial && label === "answerable" && evidence.length === 0)}
          onClick={() => void submit()}>
          {submitLabel}
        </Button>
        {onCancel && <Button size="small" disabled={submitting} onClick={onCancel}>取消</Button>}
      </Space>
    </div>
  );
}

export function LiveEvaluation({ client, kbId }: { client: ApiClient; kbId: string }) {
  const [cases, setCases] = useState<EvalCase[]>();
  const [docs, setDocs] = useState<DocumentInfo[]>([]);
  const [editing, setEditing] = useState<EvalCase>();
  // 创建成功后重新挂载表单以清空输入，避免重复提交同一用例。
  const [formKey, setFormKey] = useState(0);
  const [panelKey, setPanelKey] = useState(0);
  const [approveImport, setApproveImport] = useState(false);
  const [error, setError] = useState<ApiError>();

  const fail = (e: unknown) => setError(e instanceof ApiError ? e : new ApiError(0, "NETWORK", "请求失败"));
  const gate = useRef(createRequestGate());
  const load = useCallback(async () => {
    const g = gate.current.next();
    try {
      const [c, d] = await Promise.all([client.evalCases(kbId), client.documents(kbId)]);
      if (!gate.current.isCurrent(g)) return;
      setCases(c);
      setDocs(d);
    } catch (e) {
      fail(e);
    }
  }, [client, kbId]);
  useEffect(() => void load(), [load]);

  const act = async (fn: () => Promise<unknown>) => {
    setError(undefined);
    try {
      await fn();
      setEditing(undefined);
    } catch (e) {
      fail(e);
    }
    await load();
  };

  const importFile = async (file: File) => {
    const text = await file.text();
    const contentType = file.name.toLowerCase().endsWith(".csv") ? "text/csv" : "application/json";
    await act(() => client.importEvalCases(kbId, text, contentType, approveImport));
    setPanelKey((value) => value + 1);
  };

  const example = cases?.some((item) => item.source.startsWith("example:")) ?? false;

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">评测中心</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            {cases ? `${cases.length} 个用例 · ${cases.filter((c) => c.status === "approved").length} 个已批准` : "加载中"}
            · 图表只使用已保存的评测运行
          </Typography.Text>
        </div>
      </div>
      {error && <div className="scaffold-alert"><ErrorState code={error.code} message={error.message} requestId={error.requestId} /></div>}
      {example && <Alert type="warning" showIcon className="scaffold-alert" title="当前知识库含示例评测集" description="来源以 example: 开头的用例是虚构示例，不是业务数据。" />}
      <EvalPanel client={client} kbId={kbId} cases={cases ?? []} refreshKey={panelKey} />
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={15}>
          <Card size="small" className="side-card" title="评测用例">
            <div className="eval-toolbar">
              <Checkbox checked={approveImport} onChange={(event) => setApproveImport(event.target.checked)}>导入后批准</Checkbox>
              <label className="card-unit">
                导入 JSON / CSV
                <input aria-label="导入评测集" type="file" accept=".json,.csv,application/json,text/csv" onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = "";
                  if (file) void importFile(file);
                }} />
              </label>
            </div>
            {!cases ? <LoadingList /> : (
              <CaseList cases={cases} onApprove={(c) => void act(() => client.approveEvalCase(kbId, c.id))} onEdit={setEditing} />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Card size="small" className="side-card" title={editing ? `补齐 · ${editing.question}` : "新建用例"}>
            {editing ? (
              <EvidenceForm key={editing.id} docs={docs} initial={editing} submitLabel="保存" onCancel={() => setEditing(undefined)}
                onSubmit={(v) => act(async () => {
                  await client.setEvalEvidence(kbId, editing.id, v.evidence);
                  await client.setEvalCaseDetails(kbId, editing.id, v.details);
                })} />
            ) : (
              <EvidenceForm key={formKey} docs={docs} submitLabel="创建候选"
                onSubmit={(v) => act(async () => {
                  await client.createEvalCase(kbId, { question: v.question, label: v.label, ...v.details, ...(v.evidence.length ? { evidence: v.evidence } : {}) });
                  setFormKey((value) => value + 1);
                })} />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
