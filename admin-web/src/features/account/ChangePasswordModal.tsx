import { Alert, Button, Form, Input, Modal } from "antd";
import { useState } from "react";
import { ApiError, type ApiClient } from "../../api/client";
import { useNotice } from "../../notice";
import { passwordProblem } from "./passwordRules";

interface Values {
  current: string;
  next: string;
  confirm: string;
}

const failure = (e: unknown) => {
  if (e instanceof ApiError && e.code === "INVALID_CURRENT_PASSWORD") return "当前密码不正确";
  if (e instanceof ApiError && e.code === "RATE_LIMITED") return "尝试次数过多，请稍后再试";
  return e instanceof ApiError ? `${e.message}（${e.code}）` : "请求失败";
};

/**
 * 本人修改密码。forced 用于临时密码首次登录：不能关闭，只能修改或退出登录。
 * 成功后其他设备下线、当前设备保持登录。
 */
export function ChangePasswordModal({ open, client, username, forced = false, onClose, onChanged, onLogout }: {
  open: boolean;
  client: ApiClient;
  username: string;
  forced?: boolean;
  onClose: () => void;
  onChanged: () => void;
  onLogout?: () => void;
}) {
  const [form] = Form.useForm<Values>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const notice = useNotice();

  const submit = async () => {
    // 校验不通过时表单已在字段下方提示，这里不再抛出。
    const v = await form.validateFields().catch(() => undefined);
    if (!v) return;
    setBusy(true);
    setError(undefined);
    try {
      await client.changePassword(v.current, v.next);
      notice.success("密码已修改，其他设备已下线");
      form.resetFields();
      onChanged();
    } catch (e) {
      setError(failure(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={open} title={forced ? "请先设置自己的密码" : "修改密码"} destroyOnHidden width={440}
      closable={!forced} mask={{ closable: !forced }} keyboard={!forced} onCancel={onClose}
      footer={[
        forced
          ? <Button key="logout" onClick={onLogout}>退出登录</Button>
          : <Button key="cancel" onClick={onClose}>取消</Button>,
        <Button key="ok" type="primary" loading={busy} onClick={() => void submit()}>修改密码</Button>,
      ]}>
      {forced && <Alert type="info" showIcon className="modal-alert" title="你正在使用管理员发放的临时密码，设置自己的密码后继续使用。" />}
      {error && <Alert type="error" showIcon className="modal-alert" title={error} />}
      <Form form={form} layout="vertical" requiredMark={false} onFinish={() => void submit()}>
        <Form.Item name="current" label={forced ? "临时密码" : "当前密码"} rules={[{ required: true, message: "请输入当前密码" }]}>
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item name="next" label="新密码" extra="至少 10 位，不能与用户名相同"
          rules={[{ required: true, message: "请输入新密码" }, {
            validator: async (_, value: string | undefined) => {
              const problem = value ? passwordProblem(username, value) : undefined;
              if (problem) throw new Error(problem);
            },
          }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="confirm" label="确认新密码" dependencies={["next"]} className="form-last"
          rules={[{ required: true, message: "请再输入一次新密码" }, ({ getFieldValue }) => ({
            validator: async (_, value: string | undefined) => {
              if (value && value !== getFieldValue("next")) throw new Error("两次输入的新密码不一致");
            },
          })]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
