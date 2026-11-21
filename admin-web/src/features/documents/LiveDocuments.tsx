import { Button, Card, Drawer, Popconfirm, Space, Table, Tag, Typography, Upload } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { createRequestGate } from "../../api/requestGate";
import { ApiError, type ApiClient, type DeletionInfo, type DocumentInfo, type Status, type VersionInfo } from "../../api/client";
import { IndexStatusTag, type IndexStatus } from "../../components/IndexStatusTag";
import { EmptyState, ErrorState, LoadingList } from "../../components/StateViews";
import { formatInstant } from "../../format";
import { usePollingLoad } from "../../hooks/usePollingLoad";
import { ChunkEditor } from "./ChunkEditor";
import { UploadDocumentModal } from "./UploadDocumentModal";
import { useNotice } from "../../notice";

export const indexState = (s: VersionInfo["indexStatus"]): IndexStatus =>
  s === "ready" ? "indexed" : s === "failed" ? "failed" : s === "processing" ? "parsing" : "pending";

/**
 * "pending" 也包括从未获得内容的版本，因此只在上传或重建索引后立即轮询。
 * 删除清理只在删除后立即轮询，这样清理 worker 停止时不会导致无限轮询。
 */
export function shouldPoll(docs: DocumentInfo[] | undefined, watchBudget: number) {
  const states = docs?.flatMap((d) => d.versions.map((v) => v.indexStatus)) ?? [];
  // 已就绪版本重建时索引状态保持 ready，所以观察期内一律轮询，重建成功或失败都能在页面看到。
  return states.includes("processing") || watchBudget > 0;
}

export function deletionLabel(status: DeletionInfo["status"]) {
  if (status === "done") return "已删除 · 清理完成";
  if (status === "failed") return "访问已停用 · 清理失败";
  return "访问已停用 · 清理进行中";
}

function DeletionTag({ deletion }: { deletion: DeletionInfo }) {
  const color = deletion.status === "failed" ? "red" : deletion.status === "done" ? "default" : "orange";
  return (
    <Space orientation="vertical" size={0}>
      <Tag color={color}>{deletionLabel(deletion.status)}</Tag>
      {deletion.error && <span className="card-unit">{deletion.error}</span>}
    </Space>
  );
}

const WATCH_POLLS = 30;

export function businessDate(instant: string | null, zone: string) {
  if (!instant) return undefined;
  return new Intl.DateTimeFormat("sv-SE", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(instant));
}

function businessState(v: VersionInfo) {
  if (v.withdrawn) return <Tag color="default">已撤回</Tag>;
  if (v.published) return <Tag color="green">已发布</Tag>;
  if (v.approved) return <Tag>已批准</Tag>;
  return <Tag>草稿</Tag>;
}

const failure = (e: unknown) => (e instanceof ApiError ? `${e.message}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）` : "请求失败");

/** 来自服务端的所选知识库文档：业务状态与索引状态分开显示。 */
export function LiveDocuments({ client, kbId, status }: { client: ApiClient; kbId: string; status: Status | undefined }) {
  const zone = status?.businessTimeZone ?? "Asia/Shanghai";
  const [docs, setDocs] = useState<DocumentInfo[]>();
  const [loadError, setLoadError] = useState<string>();
  const [upload, setUpload] = useState<{ docId?: string }>();
  const notice = useNotice();

  const gate = useRef(createRequestGate());
  const load = useCallback(async () => {
    const g = gate.current.next();
    try {
      const docs = await client.documents(kbId);
      if (gate.current.isCurrent(g)) {
        setDocs(docs);
        setLoadError(undefined);
      }
    } catch (e) {
      if (gate.current.isCurrent(g)) setLoadError(failure(e));
    }
  }, [client, kbId]);

  useEffect(() => {
    setDocs(undefined);
    setLoadError(undefined);
    void load();
  }, [load]);

  const [watch, setWatch] = useState(0);
  const [editing, setEditing] = useState<{ doc: string; version: string; title: string }>();
  const poll = shouldPoll(docs, watch);
  usePollingLoad(poll, 2000, async () => {
    setWatch((w) => Math.max(0, w - 1));
    await load();
  });

  /** 操作结果用浮层提示；失败也只是提示，列表随后刷新为服务端的真实状态。 */
  const act = async (fn: () => Promise<unknown>, done: string, watchAfter = false) => {
    if (watchAfter) setWatch(WATCH_POLLS);
    try {
      await fn();
      notice.success(done);
    } catch (e) {
      notice.error(`操作失败：${failure(e)}`, 5);
    }
    await load();
  };

  const versionColumns = (doc: DocumentInfo) => [
    { title: "版本", dataIndex: "versionLabel" },
    {
      title: "生效区间（业务日期）",
      key: "interval",
      render: (_: unknown, v: VersionInfo) => `${businessDate(v.effectiveFrom, zone)} → ${businessDate(v.effectiveUntil, zone) ?? "长期"}`,
    },
    { title: "业务状态", key: "business", render: (_: unknown, v: VersionInfo) => businessState(v) },
    {
      title: "索引状态",
      key: "index",
      render: (_: unknown, v: VersionInfo) => (
        <Space orientation="vertical" size={0}>
          <IndexStatusTag status={indexState(v.indexStatus)} />
          {v.indexError && <span className="card-unit">{v.indexError}</span>}
        </Space>
      ),
    },
    {
      title: "操作",
      key: "actions",
      render: (_: unknown, v: VersionInfo) => (
        <Space wrap size={4}>
          {v.indexStatus === "pending" && (
            <Upload accept=".md,.markdown,.txt" showUploadList={false} beforeUpload={(file) => {
              void act(() => client.upload(kbId, doc.id, v.id, file), "已上传，正在建立索引", true);
              return false;
            }}>
              <Button size="small">上传内容</Button>
            </Upload>
          )}
          <Button size="small" disabled={v.approved || v.withdrawn} onClick={() => act(() => client.transition(kbId, doc.id, v.id, "approve"), "已批准")}>批准</Button>
          <Button size="small" type={v.approved && !v.published && !v.withdrawn ? "primary" : "default"} disabled={!v.approved || v.published || v.withdrawn}
            onClick={() => act(() => client.transition(kbId, doc.id, v.id, "publish"), "已发布")}>发布</Button>
          <Popconfirm
            title="撤回该版本？"
            description="立即停止检索、引用与问答，依赖它的历史回答将被隐藏；撤回后不能再批准或发布该版本。"
            okText="确认撤回"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            disabled={v.withdrawn}
            onConfirm={() => act(() => client.transition(kbId, doc.id, v.id, "withdraw"), "已撤回")}
          >
            <Button size="small" danger disabled={v.withdrawn}>撤回</Button>
          </Popconfirm>
          <Button size="small" disabled={v.indexStatus === "pending" || v.indexStatus === "processing"} onClick={() => act(() => client.reindex(kbId, doc.id, v.id), "已开始重建索引", true)}>重建索引</Button>
          <Button size="small" onClick={() => setEditing({ doc: doc.id, version: v.id, title: `${doc.title} ${v.versionLabel}` })}>编辑切块</Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">文档管理</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">
            已连接本地服务 · 格式 {status?.uploadFormats.join(" / ") ?? "—"} · 单文件上限 {status ? Math.round(status.maxUploadBytes / 1048576) : "—"} MB · 业务时区 {zone}
          </Typography.Text>
        </div>
        <Button type="primary" onClick={() => setUpload({})}>上传文档</Button>
      </div>
      <UploadDocumentModal open={upload !== undefined} client={client} kbId={kbId} docs={docs ?? []} presetDocId={upload?.docId}
        onClose={() => setUpload(undefined)}
        onUploaded={(title, published) => {
          setUpload(undefined);
          setWatch(WATCH_POLLS);
          notice.success(published ? `《${title}》已上传并发布，正在建立索引` : `《${title}》已上传，批准并发布后可用于问答`);
          void load();
        }} />
      <Drawer open={editing !== undefined} title={editing ? `编辑切块 · ${editing.title}` : "编辑切块"} size={720}
        closable={{ placement: "end" }} onClose={() => setEditing(undefined)} destroyOnHidden>
        {editing && (
          <ChunkEditor client={client} kbId={kbId} documentId={editing.doc} versionId={editing.version} title={editing.title}
            onSaved={() => { notice.success("切块已保存并重新向量化"); void load(); }} />
        )}
      </Drawer>
      <Card className="doc-table">
        {docs === undefined && !loadError && <div className="state-pad"><LoadingList /></div>}
        {docs === undefined && loadError && <div className="state-pad"><ErrorState code="LOAD_FAILED" message={`文档列表读取失败：${loadError}`} onRetry={() => void load()} /></div>}
        {docs?.length === 0 && (
          <EmptyState icon="doc" title="暂无文档" description="点右上角「上传文档」选择 .md / .txt；版本需批准、发布、处于有效期且索引就绪后才能用于问答。"
            action={<Button type="primary" onClick={() => setUpload({})}>上传文档</Button>} />
        )}
        {docs && docs.length > 0 && <Table<DocumentInfo>
          rowKey="id"
          dataSource={docs ?? []}
          pagination={false}
          scroll={{ x: 760 }}
          rowClassName={(d) => (d.deletion ? "doc-deleted" : "")}
          expandable={{
            rowExpandable: (doc) => !doc.deletion,
            expandedRowRender: (doc) => (
              <div className="version-panel">
                <Table<VersionInfo> size="small" rowKey="id" pagination={false} dataSource={doc.versions} columns={versionColumns(doc)}
                  locale={{ emptyText: "暂无版本" }} scroll={{ x: 760 }} />
              </div>
            ),
          }}
          columns={[
            { title: "文档", dataIndex: "title", fixed: "left", width: 200, ellipsis: true, render: (title: string) => <span className="clip" title={title}>{title}</span> },
            { title: "版本数", key: "count", className: "mono", render: (_, d) => d.versions.length },
            {
              title: "最新版本索引",
              key: "latest",
              render: (_, d) => {
                if (d.deletion) return <DeletionTag deletion={d.deletion} />;
                const latest = d.versions.at(-1);
                return latest ? <IndexStatusTag status={indexState(latest.indexStatus)} /> : "—";
              },
            },
            { title: "创建时间", dataIndex: "createdAt", className: "mono", width: 180, render: (v: string) => formatInstant(v, zone) },
            {
              title: "操作",
              key: "op",
              render: (_, d) => {
                if (d.deletion?.status === "failed") {
                  return <Button size="small" onClick={() => act(() => client.deleteDocument(kbId, d.id), "已重新排队清理", true)}>重试清理</Button>;
                }
                if (d.deletion) return null;
                return (
                  <Space size={4}>
                  <Button size="small" onClick={() => setUpload({ docId: d.id })}>上传新版本</Button>
                  <Popconfirm
                    title="删除该文档的全部版本？"
                    description="立即停止检索、引用与问答，依赖它的历史回答将被隐藏；原文、切块与向量随后在后台清理，不可恢复。数据库备份中的副本不在此清理范围内。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => act(() => client.deleteDocument(kbId, d.id), "已删除，后台清理中", true)}
                  >
                    <Button size="small" danger>删除</Button>
                  </Popconfirm>
                  </Space>
                );
              },
            },
          ]}
        />}
      </Card>
    </div>
  );
}
