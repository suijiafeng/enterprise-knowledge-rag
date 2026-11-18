import { Alert, Card, Col, Row, Table, Tag, Typography } from "antd";
import type { EvalRun } from "../../api/client";
import { metricCards } from "./LiveEvaluation";
import { decimal, metricRows, parameterRows, ratioText } from "./evalPanel";

export interface ExampleSnapshot {
  example: true;
  notice: string;
  runs: EvalRun[];
}

/** 未登录时只展示随仓库发布的示例快照，没有运行、导入或知识库数据。 */
export function PublicEvaluation({ snapshot }: { snapshot: ExampleSnapshot }) {
  const selected = snapshot.runs[0];
  const compared = snapshot.runs[1];
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">评测中心</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">未登录 · 只读示例 · 不能查看企业知识库里的评测</Typography.Text>
        </div>
        <Tag color="gold">示例</Tag>
      </div>
      <Alert type="warning" showIcon className="scaffold-alert" title="示例运行" description={snapshot.notice} />
      {snapshot.runs.length === 0 && <Card>还没有随仓库发布的示例运行。</Card>}
      {selected && (
        <>
          <Typography.Text type="secondary" className="page-sub">
            示例运行 · K {selected.k} · {selected.rerankEnabled ? `重排前 ${selected.rerankPool ?? "—"} 条` : "未重排"}
            {selected.judge ? ` · 评审 ${selected.judge}` : ""} · 模型 {selected.chatModel ?? selected.model ?? "—"}
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
          </Row>
          <Card size="small" title="示例运行列表">
            <Table<EvalRun> size="small" rowKey="id" pagination={false} dataSource={snapshot.runs} scroll={{ x: 640 }}
              columns={[
                { title: "K", dataIndex: "k" },
                { title: "重排", key: "rerank", render: (_, run) => run.rerankEnabled ? `前 ${run.rerankPool}` : "关闭" },
                { title: "Hit", key: "hit", render: (_, run) => ratioText(run.summary.hit.num, run.summary.hit.den) },
                { title: "MRR", key: "mrr", render: (_, run) => decimal(run.summary.mrr) },
                { title: "忠实度", key: "faith", render: (_, run) => decimal(run.summary.faithfulness) },
              ]} />
          </Card>
        </>
      )}
      {selected && compared && (
        <Card size="small" title="示例对比">
          <Table size="small" pagination={false} rowKey="key" dataSource={[...parameterRows(selected, compared), ...metricRows(selected, compared)]}
            columns={[
              { title: "项目", dataIndex: "name" },
              { title: "较新一次", dataIndex: "left" },
              { title: "另一次", dataIndex: "right" },
              { title: "差", dataIndex: "delta", render: (value: string) => value || "—" },
            ]} />
        </Card>
      )}
    </div>
  );
}
