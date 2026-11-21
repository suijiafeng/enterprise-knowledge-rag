import { Alert, Button, Card, Checkbox, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, type AccountInfo, type ApiClient, type Role } from "../../api/client";
import { useNotice } from "../../notice";

export const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: "EMPLOYEE", label: "普通员工" },
  { value: "KB_OPERATOR", label: "知识库运营" },
  { value: "IT_ADMIN", label: "IT 管理员" },
];
const ROLE_LABEL = Object.fromEntries(ROLE_OPTIONS.map((r) => [r.value, r.label])) as Record<Role, string>;

const failure = (e: unknown) => {
  if (e instanceof ApiError && e.status === 409) return "用户名已被占用";
  return e instanceof ApiError ? `${e.message}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）` : "请求失败";
};

/** 一次性临时密码：只在这里显示一次，关闭后无法再查看。 */
function TemporaryPassword({ shown, onClose }: { shown: { username: string; password: string; created: boolean } | undefined; onClose: () => void }) {
  const notice = useNotice();
  return (
    <Modal open={shown !== undefined} title={shown?.created ? "账号已创建" : "密码已重置"} onCancel={onClose} destroyOnHidden width={440}
      footer={<Button type="primary" onClick={onClose}>我已转交</Button>}>
      {shown && (
        <>
          <p className="card-unit">把下面的临时密码当面或通过企业内部渠道转交给 <b>{shown.username}</b>。只显示这一次；对方首次登录后需改成自己的密码。</p>
          <div className="temp-password">
            <Typography.Text code copyable={{ text: shown.password, onCopy: () => notice.success("已复制") }} className="mono">{shown.password}</Typography.Text>
          </div>
          {!shown.created && <p className="card-unit">该账号的所有设备已下线。</p>}
        </>
      )}
    </Modal>
  );
}

interface NewAccountValues {
  username: string;
  displayName?: string;
  roles: Role[];
}

function CreateAccountModal({ open, client, onClose, onCreated }: {
  open: boolean;
  client: ApiClient;
  onClose: () => void;
  onCreated: (username: string, password: string) => void;
}) {
  const [form] = Form.useForm<NewAccountValues>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  useEffect(() => { if (open) setError(undefined); }, [open]);

  const submit = async () => {
    // 校验不通过时表单已在字段下方提示，这里不再抛出。
    const v = await form.validateFields().catch(() => undefined);
    if (!v) return;
    setBusy(true);
    setError(undefined);
    try {
      const displayName = v.displayName?.trim();
      const created = await client.createAccount({ username: v.username.trim().toLowerCase(), roles: v.roles, ...(displayName ? { displayName } : {}) });
      form.resetFields();
      onCreated(created.username, created.temporaryPassword);
    } catch (e) {
      setError(failure(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={open} title="新建账号" okText="创建" cancelText="取消" onOk={() => void submit()} onCancel={onClose} confirmLoading={busy}
      destroyOnHidden width={460}>
      {error && <Alert type="error" showIcon className="modal-alert" title={error} />}
      <Form form={form} layout="vertical" requiredMark={false} initialValues={{ roles: ["EMPLOYEE"] }}>
        <Form.Item name="username" label="用户名" extra="3–64 位小写字母、数字或 . _ -，登录用"
          rules={[{ required: true, message: "请填写用户名" }, { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/, message: "3–64 位字母、数字或 . _ -" }]}>
          <Input placeholder="如：lin.xiao" autoComplete="off" />
        </Form.Item>
        <Form.Item name="displayName" label="显示名" extra="留空时用用户名" rules={[{ max: 100 }]}>
          <Input placeholder="如：林晓" />
        </Form.Item>
        <Form.Item name="roles" label="角色" className="form-last" rules={[{ required: true, type: "array", min: 1, message: "至少选择一个角色" }]}>
          <Checkbox.Group options={ROLE_OPTIONS} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function EditRolesModal({ account, client, onClose, onSaved }: {
  account: AccountInfo | undefined;
  client: ApiClient;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [roles, setRoles] = useState<Role[]>([]);
  const [busy, setBusy] = useState(false);
  const notice = useNotice();
  useEffect(() => { if (account) setRoles(account.roles); }, [account]);

  const save = async () => {
    if (!account) return;
    setBusy(true);
    try {
      await client.updateAccount(account.id, { roles });
      notice.success("角色已更新，下一次请求立即生效");
      onSaved();
    } catch (e) {
      notice.error(failure(e), 5);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={account !== undefined} title={account ? `修改角色 · ${account.displayName ?? account.username}` : "修改角色"} okText="保存" cancelText="取消"
      onOk={() => void save()} onCancel={onClose} confirmLoading={busy} okButtonProps={{ disabled: roles.length === 0 }} destroyOnHidden width={420}>
      <Checkbox.Group options={ROLE_OPTIONS} value={roles} onChange={(v) => setRoles(v as Role[])} />
      {roles.length === 0 && <p className="card-unit">至少保留一个角色；不再使用的账号请停用。</p>}
    </Modal>
  );
}

/** IT 管理员管理本企业登录账号：新建、改角色、停用/启用、重置密码、踢下线。不能操作自己的账号。 */
export function AccountsPanel({ client, selfId }: { client: ApiClient; selfId: string | undefined }) {
  const [accounts, setAccounts] = useState<AccountInfo[]>();
  const [loadError, setLoadError] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<AccountInfo>();
  const [shown, setShown] = useState<{ username: string; password: string; created: boolean }>();
  const notice = useNotice();

  const load = useCallback(async () => {
    try {
      setAccounts(await client.accounts());
      setLoadError(undefined);
    } catch (e) {
      setLoadError(failure(e));
    }
  }, [client]);
  useEffect(() => void load(), [load]);

  const act = async (fn: () => Promise<unknown>, done: string) => {
    try {
      await fn();
      notice.success(done);
    } catch (e) {
      notice.error(failure(e), 5);
    }
    await load();
  };

  const reset = async (a: AccountInfo) => {
    try {
      const { temporaryPassword } = await client.resetPassword(a.id);
      setShown({ username: a.username, password: temporaryPassword, created: false });
    } catch (e) {
      notice.error(failure(e), 5);
    }
    await load();
  };

  return (
    <Card size="small" className="debug-card" title="登录账号" extra={<Button type="primary" size="small" onClick={() => setCreating(true)}>新建账号</Button>}>
      {loadError && <Alert type="error" showIcon className="scaffold-alert" title={`账号列表读取失败：${loadError}`} action={<Button size="small" onClick={() => void load()}>重试</Button>} />}
      <Table<AccountInfo>
        size="small"
        rowKey="id"
        pagination={false}
        loading={accounts === undefined && !loadError}
        dataSource={accounts ?? []}
        scroll={{ x: 720 }}
        locale={{ emptyText: "暂无账号" }}
        rowClassName={(a) => (a.disabled ? "row-disabled" : "")}
        columns={[
          {
            title: "账号",
            key: "who",
            render: (_, a) => (
              <div className="user-cell">
                <span>{a.displayName ?? a.username}{a.id === selfId && <Tag className="tag-inline">当前账号</Tag>}</span>
                <span className="card-unit mono">{a.displayName ? a.username : "未设置显示名"}</span>
              </div>
            ),
          },
          { title: "角色", key: "roles", render: (_, a) => <Space size={4} wrap>{a.roles.map((r) => <Tag key={r}>{ROLE_LABEL[r] ?? r}</Tag>)}</Space> },
          {
            title: "状态",
            key: "state",
            width: 150,
            render: (_, a) => (
              <Space size={4} wrap>
                {a.disabled ? <Tag color="default">已停用</Tag> : <Tag color="green">正常</Tag>}
                {a.mustChangePassword && !a.disabled && <Tag color="orange">待改密</Tag>}
              </Space>
            ),
          },
          { title: "在线", dataIndex: "activeSessions", width: 64, className: "mono" },
          {
            title: "",
            key: "op",
            align: "right",
            width: 300,
            render: (_, a) => a.id === selfId ? <span className="card-unit">在右上角修改自己的密码</span> : (
              <Space size={4} wrap className="row-actions">
                <Button size="small" onClick={() => setEditing(a)}>角色</Button>
                <Popconfirm title={`重置 ${a.username} 的密码？`} description="生成一次性临时密码，并让该账号所有设备下线。" okText="重置" cancelText="取消"
                  onConfirm={() => reset(a)}>
                  <Button size="small">重置密码</Button>
                </Popconfirm>
                {a.activeSessions > 0 && (
                  <Button size="small" onClick={() => act(() => client.revokeSessions(a.id), "已让该账号所有设备下线")}>下线</Button>
                )}
                {a.disabled ? (
                  <Button size="small" onClick={() => act(() => client.updateAccount(a.id, { disabled: false }), "已启用")}>启用</Button>
                ) : (
                  <Popconfirm title={`停用 ${a.username}？`} description="立即下线且不能再登录，可随时重新启用。" okText="停用" cancelText="取消"
                    okButtonProps={{ danger: true }} onConfirm={() => act(() => client.updateAccount(a.id, { disabled: true }), "已停用")}>
                    <Button size="small" danger>停用</Button>
                  </Popconfirm>
                )}
              </Space>
            ),
          },
        ]}
      />
      <CreateAccountModal open={creating} client={client} onClose={() => setCreating(false)}
        onCreated={(username, password) => { setCreating(false); setShown({ username, password, created: true }); void load(); }} />
      <EditRolesModal account={editing} client={client} onClose={() => setEditing(undefined)} onSaved={() => { setEditing(undefined); void load(); }} />
      <TemporaryPassword shown={shown} onClose={() => setShown(undefined)} />
    </Card>
  );
}
