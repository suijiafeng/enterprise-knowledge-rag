import { Alert, Checkbox, DatePicker, Form, Input, Modal, Select, Upload } from "antd";
import dayjs, { type Dayjs } from "dayjs";
import { useEffect, useState } from "react";
import { ApiError, type ApiClient, type DocumentInfo } from "../../api/client";

const NEW = "__new__";
export const ACCEPT = ".md,.markdown,.txt,.pdf,.docx";

export interface UploadValues {
  target: string;
  title?: string;
  versionLabel: string;
  effectiveFrom: Dayjs;
  effectiveUntil?: Dayjs | null;
  publish: boolean;
}

/** 文件名去掉扩展名，作为新文档的默认标题。 */
export const titleFromFile = (name: string) => name.replace(/\.(md|markdown|txt|pdf|docx)$/i, "").trim().slice(0, 200);

/** 已有文档的下一个版本号：v{n+1}。 */
export const nextVersionLabel = (doc: DocumentInfo | undefined) => `v${(doc?.versions.length ?? 0) + 1}`;

const failure = (e: unknown) => (e instanceof ApiError ? `${e.message}（${e.code}${e.requestId ? ` · ${e.requestId}` : ""}）` : "请求失败");

/**
 * 上传文档：一步完成「新建文档（或选已有文档）→ 新建版本 → 上传内容 →（可选）发布」。
 * 部署不要求审批时（默认）发布即由发布人批准；要求审批时只上传，等有权限的人批准后再发布。
 * 页脚按钮在右下角，关闭图标在右上角；失败时停在弹窗内说明是哪一步失败。
 */
export function UploadDocumentModal({ open, client, kbId, docs, presetDocId, requireApproval = false, onClose, onUploaded }: {
  open: boolean;
  requireApproval?: boolean;
  client: ApiClient;
  kbId: string;
  docs: DocumentInfo[];
  presetDocId?: string | undefined;
  onClose: () => void;
  onUploaded: (title: string, published: boolean) => void;
}) {
  const [form] = Form.useForm<UploadValues>();
  const [file, setFile] = useState<File>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const target = Form.useWatch("target", form);
  const selectable = docs.filter((d) => !d.deletion);

  const preset = selectable.find((d) => d.id === presetDocId);
  useEffect(() => {
    if (!open) return;
    setFile(undefined);
    setError(undefined);
  }, [open]);

  const submit = async () => {
    const values = await form.validateFields();
    if (!file) {
      setError("请选择要上传的文件");
      return;
    }
    setBusy(true);
    setError(undefined);
    let step = "新建文档";
    try {
      const existing = selectable.find((d) => d.id === values.target);
      const title = existing?.title ?? (values.title?.trim() || titleFromFile(file.name));
      const docId = existing?.id ?? (await client.createDocument(kbId, title)).id;
      step = "新建版本";
      const from = values.effectiveFrom.format("YYYY-MM-DD");
      const until = values.effectiveUntil ? values.effectiveUntil.format("YYYY-MM-DD") : undefined;
      const version = await client.createVersion(kbId, docId, until ? { versionLabel: values.versionLabel.trim(), effectiveFrom: from, effectiveUntil: until }
        : { versionLabel: values.versionLabel.trim(), effectiveFrom: from });
      step = "上传内容";
      await client.upload(kbId, docId, version.id, file);
      const publish = !requireApproval && values.publish !== false;
      if (publish) {
        step = "发布";
        await client.transition(kbId, docId, version.id, "publish");
      }
      onUploaded(title, publish);
    } catch (e) {
      setError(`${step}失败：${failure(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const publish = Form.useWatch("publish", form) ?? true;
  return (
    <Modal open={open} title="上传文档" okText={publish && !requireApproval ? "上传并发布" : "上传"} cancelText="取消" onOk={() => void submit()} onCancel={onClose}
      confirmLoading={busy} okButtonProps={{ disabled: !file }} destroyOnHidden width={520}>
      {error && <Alert type="error" showIcon title={error} className="modal-alert" />}
      <Form form={form} layout="vertical" requiredMark={false} className="upload-form"
        initialValues={{ target: preset?.id ?? NEW, title: "", versionLabel: nextVersionLabel(preset), effectiveFrom: dayjs(), effectiveUntil: null, publish: true }}>
        <Form.Item label="文件" required>
          <Upload.Dragger accept={ACCEPT} maxCount={1} multiple={false}
            fileList={file ? [{ uid: "file", name: file.name, status: "done", size: file.size }] : []}
            beforeUpload={(f) => {
              setFile(f);
              if (!form.getFieldValue("title")) form.setFieldValue("title", titleFromFile(f.name));
              return false;
            }}
            onRemove={() => { setFile(undefined); return true; }}>
            <p className="upload-hint-main">点击或拖入文件</p>
            <p className="upload-hint">.md / .txt（UTF-8）· .pdf（需有文字层）· .docx</p>
          </Upload.Dragger>
        </Form.Item>
        <Form.Item name="target" label="归属">
          <Select options={[{ value: NEW, label: "新建文档" }, ...selectable.map((d) => ({ value: d.id, label: `${d.title}（新版本）` }))]}
            onChange={(v: string) => form.setFieldValue("versionLabel", nextVersionLabel(selectable.find((d) => d.id === v)))} />
        </Form.Item>
        {target === NEW && (
          <Form.Item name="title" label="文档标题" rules={[{ max: 200 }]} extra="留空时用文件名">
            <Input placeholder="如：年假办法" />
          </Form.Item>
        )}
        <div className="form-row">
          <Form.Item name="versionLabel" label="版本号" rules={[{ required: true, whitespace: true, message: "请填写版本号" }, { max: 50 }]}>
            <Input placeholder="v1" />
          </Form.Item>
          <Form.Item name="effectiveFrom" label="生效日期" rules={[{ required: true, message: "请选择生效日期" }]}>
            <DatePicker className="full" allowClear={false} />
          </Form.Item>
          <Form.Item name="effectiveUntil" label="失效日期">
            <DatePicker className="full" placeholder="长期有效" />
          </Form.Item>
        </div>
        {requireApproval ? (
          <Alert type="info" showIcon className="form-last" title="本部署要求审批：上传后需由有权限的人在文档列表里批准，再发布。" />
        ) : (
          <Form.Item name="publish" valuePropName="checked" className="form-last">
            <Checkbox>上传后直接发布（索引就绪后即可用于问答）</Checkbox>
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
