import { Button, Card, Col, Row, Table, Tag, Typography } from "antd";
import { IndexStatusTag, type IndexStatus } from "../../components/IndexStatusTag";
import { MetricStrip } from "../../components/MetricStrip";
import { DailyQuestionsChart } from "./DailyQuestionsChart";
import type { OverviewData } from "./fixtures";

const tone = (trend: OverviewData["metrics"][number]["trend"]) => (trend === "up" || trend === "bad" ? trend : "flat");

export function OverviewPage({ data, live = false, onNewQuestion, onOpenDocuments, onOpenRecords }: {
  data: OverviewData;
  live?: boolean;
  onNewQuestion?: () => void;
  onOpenDocuments?: () => void;
  onOpenRecords?: () => void;
}) {
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">知识库总览</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            {live ? `实时数据 · 刷新于 ${data.updatedAt}` : `示例数据更新于 ${data.updatedAt} · 未登录，不会自动刷新`}
          </Typography.Text>
        </div>
        {live && onNewQuestion ? <Button type="primary" onClick={onNewQuestion}>新建问答</Button> : null}
      </div>

      <MetricStrip items={data.metrics.map((metric) => ({ title: metric.title, value: metric.value, note: metric.delta, trend: tone(metric.trend) }))} />

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Card title={<>近 7 日问答量 <span className="card-unit">单位：次</span></>}>
            <DailyQuestionsChart data={data.dailyQuestions} />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title={<>向量库健康 <Tag color={live ? "green" : "default"}>{live ? "已连接" : "未连接"}</Tag></>}>
            <dl className="kv-list">
              {data.vectorStore.map((row) => (
                <div key={row.label} className="kv-row">
                  <dt>{row.label}</dt>
                  <dd>{row.value}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </Col>

        <Col xs={24} xl={12}>
          <Card title="最近入库文档" extra={onOpenDocuments ? <Button type="link" size="small" onClick={onOpenDocuments}>文档管理</Button> : null}>
            <Table
              size="small"
              pagination={false}
              rowKey="name"
              dataSource={data.recentDocuments}
              scroll={{ x: 560 }}
              columns={[
                { title: "文档", dataIndex: "name", fixed: "left", width: 220, ellipsis: true, render: (name: string) => <span className="clip" title={name}>{name}</span> },
                { title: "切块", dataIndex: "chunks", render: (v: number | null) => v ?? "—" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (s: IndexStatus) => <IndexStatusTag status={s} />,
                },
                { title: "更新时间", dataIndex: "updatedAt", className: "mono" },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title="最近问答" extra={onOpenRecords ? <Button type="link" size="small" onClick={onOpenRecords}>问答记录</Button> : null}>
            <Table
              size="small"
              pagination={false}
              rowKey="question"
              dataSource={data.recentQuestions}
              scroll={{ x: 480 }}
              columns={[
                { title: "问题", dataIndex: "question", fixed: "left", width: 220, ellipsis: true, render: (question: string) => <span className="clip" title={question}>{question}</span> },
                { title: "引用", dataIndex: "citations", render: (n: number) => `${n} 篇` },
                { title: "时间", dataIndex: "askedAt", className: "mono" },
              ]}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}
