import { Alert, Button, Card, Col, Input, Popconfirm, Row, Select, Space, Table, Typography } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, type AdminKnowledgeBase, type ApiClient, type DirectoryUser, type Grant, type KbAction, type Me, type Role, type RolePolicy, type Rule } from "../../api/client";
import { useNotice } from "../../notice";

const ROLE_LABEL: Record<Role, string> = { IT_ADMIN: "IT 管理员", KB_OPERATOR: "知识库运营", EMPLOYEE: "普通员工" };
const ACTION_LABEL: Record<KbAction, string> = {
  ASK_AND_CITE: "问答、引用与原文",
  MANAGE_DOCUMENTS: "文档上传 / 发布 / 撤回",
  DEBUG_AND_EVALUATE: "检索调试与评测",
  READ_OTHERS_CONVERSATIONS: "查看他人问答正文",
};
const RULE_LABEL: Record<Rule, string> = { ALLOWED: "✓", REQUIRES_EXPLICIT_GRANT: "需显式授权", NEVER: "—" };

export const isUuid = (v: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.trim());

const failure = (e: unknown) => (e instanceof ApiError ? `${e.message}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）` : "请求失败");

/** 由 GET /api/policy/roles 渲染，因此页面永远不会与服务端的 RolePolicy 脱节。 */
export function RoleMatrix({ policy }: { policy: RolePolicy }) {
  return (
    <table className="role-matrix">
      <thead>
        <tr>
          <th>操作（还需知识库授权）</th>
          {policy.roles.map((r) => <th key={r}>{ROLE_LABEL[r]}</th>)}
        </tr>
      </thead>
      <tbody>
        {policy.actions.map((a) => (
          <tr key={a}>
            <td>{ACTION_LABEL[a]}</td>
            {policy.roles.map((r) => {
              const rule = policy.rules[r][a];
              return <td key={r} className={`rule-${rule.toLowerCase()}`}>{RULE_LABEL[rule]}</td>;
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Notes() {
  return (
    <Card size="small" className="debug-card" title="说明">
      <ul className="proposal-list">
        <li>用户姓名、邮箱、部门来自身份提供方同步到本地的目录（开发环境由种子数据填充）；邀请、停用和最后登录仍由身份提供方负责。目录里找不到的用户可以粘贴用户 ID 授权。</li>
        <li>角色来自登录令牌，名称为 PRD 建议映射，待业务确认（D02）。"需显式授权"的操作目前一律拒绝。</li>
        <li>授权与撤销在下一次请求立即生效，并写入审计日志；撤权后历史回答会按来源依赖隐藏。</li>
      </ul>
    </Card>
  );
}

/** 选项文本：姓名 · 部门 · 邮箱。 */
export const userLabel = (u: Pick<DirectoryUser, "displayName" | "department" | "email">) =>
  [u.displayName, u.department, u.email].filter(Boolean).join(" · ");

/** 按姓名 / 邮箱 / 部门搜索目录用户；输入完整用户 ID 且目录里没有时，仍可按 ID 授权。 */
export function UserPicker({ client, granted, value, onChange }: {
  client: ApiClient;
  granted: Set<string>;
  value: string | undefined;
  onChange: (id: string | undefined) => void;
}) {
  const [users, setUsers] = useState<DirectoryUser[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const seq = useRef(0);

  const search = useCallback(async (q: string) => {
    const mine = ++seq.current;
    setLoading(true);
    try {
      const found = await client.users(q.trim());
      if (mine === seq.current) setUsers(found);
    } catch {
      if (mine === seq.current) setUsers([]);
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    const timer = setTimeout(() => void search(query), query ? 250 : 0);
    return () => clearTimeout(timer);
  }, [query, search]);

  const options = users.map((u) => ({
    value: u.id,
    disabled: granted.has(u.id),
    label: (
      <div className="user-option">
        <span>{u.displayName}{granted.has(u.id) && <span className="card-unit"> · 已授权</span>}</span>
        <span className="card-unit">{[u.department, u.email].filter(Boolean).join(" · ")}</span>
      </div>
    ),
    title: userLabel(u),
  }));
  const raw = query.trim();
  if (isUuid(raw) && !users.some((u) => u.id === raw.toLowerCase())) {
    options.push({ value: raw.toLowerCase(), disabled: granted.has(raw.toLowerCase()), title: raw,
      label: <div className="user-option"><span>按 ID 授权</span><span className="card-unit mono">{raw}（目录中未找到）</span></div> });
  }

  return (
    <Select
      className="user-picker"
      showSearch={{ filterOption: false, onSearch: setQuery }}
      allowClear
      value={value}
      placeholder="搜索姓名、邮箱或部门"
      notFoundContent={loading ? "搜索中…" : "没有匹配的用户"}
      options={options}
      optionLabelProp="title"
      onChange={(v?: string) => onChange(v)}
      aria-label="选择用户"
    />
  );
}

function Members({ client, kb, onChanged }: { client: ApiClient; kb: AdminKnowledgeBase; onChanged: () => void }) {
  const [grants, setGrants] = useState<Grant[]>();
  const [userId, setUserId] = useState<string>();
  const [loadError, setLoadError] = useState<string>();
  const notice = useNotice();

  const load = useCallback(async () => {
    try {
      setGrants(await client.grants(kb.id));
      setLoadError(undefined);
    } catch (e) {
      setLoadError(failure(e));
    }
  }, [client, kb.id]);
  useEffect(() => void load(), [load]);

  const act = async (fn: () => Promise<unknown>, done: string) => {
    try {
      await fn();
      setUserId(undefined);
      notice.success(done);
    } catch (e) {
      notice.error(failure(e), 5);
    }
    await load();
    onChanged();
  };

  const granted = new Set((grants ?? []).map((g) => g.userId));
  return (
    <Card size="small" className="debug-card" title={<>成员 · {kb.name}</>}>
      {loadError && <Alert type="error" showIcon className="scaffold-alert" title={`成员列表读取失败：${loadError}`} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
      <div className="grant-bar">
        <UserPicker client={client} granted={granted} value={userId} onChange={setUserId} />
        <Button type="primary" disabled={!userId} onClick={() => userId && act(() => client.grant(kb.id, userId), "已授权，下一次请求立即生效")}>授权</Button>
      </div>
      <Table<Grant>
        size="small"
        rowKey="userId"
        pagination={false}
        loading={grants === undefined && !loadError}
        dataSource={grants ?? []}
        locale={{ emptyText: "暂无成员" }}
        columns={[
          {
            title: "成员",
            key: "user",
            render: (_, g) => g.displayName ? (
              <div className="user-cell">
                <span>{g.displayName}</span>
                <span className="card-unit">{[g.department, g.email].filter(Boolean).join(" · ")}</span>
              </div>
            ) : (
              <div className="user-cell">
                <span className="card-unit">未同步到目录</span>
                <span className="mono card-unit">{g.userId}</span>
              </div>
            ),
          },
          { title: "授权时间", dataIndex: "grantedAt", className: "mono", width: 170, render: (v: string) => new Date(v).toLocaleString("zh-CN") },
          {
            title: "",
            key: "op",
            width: 72,
            align: "right",
            render: (_, g) => (
              <Popconfirm
                title={`撤销 ${g.displayName ?? "该用户"} 对此知识库的访问？`}
                description="下一次请求立即生效；该用户基于此库的历史回答将被隐藏。"
                okText="撤销"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => act(() => client.revoke(kb.id, g.userId), "已撤销")}
              >
                <Button size="small" danger>撤销</Button>
              </Popconfirm>
            ),
          },
        ]}
      />
    </Card>
  );
}

/** 面向 IT 管理员的知识库授权，以及实时角色矩阵。 */
export function UsersPage({ client }: { client: ApiClient | undefined }) {
  const [me, setMe] = useState<Me>();
  const [policy, setPolicy] = useState<RolePolicy>();
  const [kbs, setKbs] = useState<AdminKnowledgeBase[]>();
  const [selected, setSelected] = useState<string>();
  const [name, setName] = useState("");
  const [error, setError] = useState<string>();
  const notice = useNotice();
  const admin = me?.roles.includes("IT_ADMIN") ?? false;

  const loadKbs = useCallback(async () => {
    if (!client) return;
    try {
      const list = await client.adminKnowledgeBases();
      setKbs(list);
      setSelected((s) => s ?? list[0]?.id);
    } catch (e) {
      setError(failure(e));
    }
  }, [client]);

  useEffect(() => {
    if (!client) return;
    Promise.all([client.me(), client.rolePolicy()]).then(([m, p]) => {
      setMe(m);
      setPolicy(p);
      if (m.roles.includes("IT_ADMIN")) void loadKbs();
    }, (e: unknown) => setError(failure(e)));
  }, [client, loadKbs]);

  const create = async () => {
    if (!client) return;
    try {
      const created = await client.createKnowledgeBase(name.trim());
      setName("");
      setSelected(created.id);
      notice.success(`已新建知识库「${created.name}」，记得给成员授权`);
    } catch (e) {
      notice.error(failure(e), 5);
    }
    await loadKbs();
  };

  const current = kbs?.find((k) => k.id === selected);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <Typography.Title level={3} className="page-title">用户与权限</Typography.Title>
          <Typography.Text type="secondary" className="page-sub">知识库级授权 · 角色来自登录令牌 · 用户目录由身份提供方管理</Typography.Text>
        </div>
      </div>
      {!client && <Alert type="info" showIcon className="scaffold-alert" title="连接本地服务后可管理授权" description="需要带 IT_ADMIN 角色的开发令牌。用户资料来自身份提供方，本页不展示虚构用户。" />}
      {error && <Alert type="error" showIcon className="scaffold-alert" title={`读取失败：${error}`} />}
      {client && me && !admin && <Alert type="warning" showIcon className="scaffold-alert" title="需要 IT 管理员角色才能管理授权" />}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          {admin && (
            <Card size="small" className="debug-card" title="知识库" extra={
              <Space>
                <Input size="small" placeholder="新知识库名称" value={name} onChange={(e) => setName(e.target.value)} aria-label="新知识库名称" maxLength={100} />
                <Button size="small" type="primary" disabled={!name.trim()} onClick={() => void create()}>新建</Button>
              </Space>
            }>
              <Table<AdminKnowledgeBase>
                size="small"
                rowKey="id"
                pagination={false}
                loading={kbs === undefined && !error}
                dataSource={kbs ?? []}
                rowClassName={(k) => (k.id === selected ? "row-selected" : "")}
                onRow={(k) => ({ onClick: () => setSelected(k.id) })}
                locale={{ emptyText: "本企业还没有知识库" }}
                columns={[
                  { title: "名称", dataIndex: "name" },
                  { title: "成员数", dataIndex: "memberCount", className: "mono" },
                  { title: "创建时间", dataIndex: "createdAt", className: "mono", render: (v: string) => new Date(v).toLocaleDateString("zh-CN") },
                ]}
              />
            </Card>
          )}
          {admin && client && current && <Members key={current.id} client={client} kb={current} onChanged={() => void loadKbs()} />}
          <Notes />
        </Col>
        <Col xs={24} xl={10}>
          <Card size="small" className="debug-card" title="角色权限矩阵" extra={<span className="card-unit">来自服务端策略</span>}>
            {policy ? <RoleMatrix policy={policy} /> : <Typography.Text type="secondary">连接后读取服务端策略。</Typography.Text>}
          </Card>
        </Col>
      </Row>
    </div>
  );
}
