import { Alert, Button, Input, Space, Typography } from "antd";
import { useEffect, useState } from "react";
import type { ApiClient, ChunkView } from "../../api/client";
import { EmptyState } from "../../components/StateViews";
import { fromServer, mergeChunks, splitChunk, toDrafts, type EditableChunk } from "./chunkEdit";

export function ChunkEditor({ client, kbId, documentId, versionId, title, onSaved }: {
  client: ApiClient;
  kbId: string;
  documentId: string;
  versionId: string;
  title: string;
  onSaved: () => void;
}) {
  const [chunks, setChunks] = useState<EditableChunk[]>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    setChunks(undefined);
    setError(undefined);
    client.chunks(kbId, documentId, versionId).then((rows) => {
      if (alive) setChunks(fromServer(rows));
    }).catch(() => {
      if (alive) setError("切块读取失败");
    });
    return () => { alive = false; };
  }, [client, kbId, documentId, versionId]);

  const update = (key: string, text: string) => setChunks((rows) => rows?.map((row) => row.key === key ? { ...row, text } : row));
  const split = (chunk: EditableChunk) => {
    const parts = splitChunk(chunk, Math.floor(chunk.text.length / 2));
    if (!parts) {
      setError("这块太短，无法拆分");
      return;
    }
    setError(undefined);
    setChunks((rows) => rows?.flatMap((row) => row.key === chunk.key ? parts : [row]));
  };
  const merge = (index: number) => setChunks((rows) => {
    if (!rows || index >= rows.length - 1) return rows;
    const next = [...rows];
    const [left, right] = [next[index]!, next[index + 1]!];
    next.splice(index, 2, mergeChunks(left, right));
    return next;
  });
  const remove = (key: string) => setChunks((rows) => {
    if (!rows || rows.length <= 1) {
      setError("至少保留一块");
      return rows;
    }
    setError(undefined);
    return rows.filter((row) => row.key !== key);
  });
  const save = async () => {
    if (!chunks) return;
    setBusy(true);
    setError(undefined);
    try {
      const saved: ChunkView[] = await client.saveChunks(kbId, documentId, versionId, toDrafts(chunks));
      setChunks(fromServer(saved));
      onSaved();
    } catch {
      setError("保存失败。偏移必须落在原文内，且需要可用的 embedding。");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="chunk-editor">
      <Typography.Text type="secondary" className="chunk-sub">{title} · 保存后重新生成这些切块的向量，并立即用于检索。原文文件不变，偏移仍指向原文字符区间。</Typography.Text>
      {error && <Alert type="error" showIcon title={error} className="modal-alert" />}
      {!chunks && !error && <EmptyState icon="doc" title="正在读取切块" description="只显示这个版本已经入库的切块。" />}
      {chunks?.length === 0 && <EmptyState icon="doc" title="还没有切块" description="上传内容并完成索引后才能编辑。" />}
      {chunks?.map((chunk, index) => (
        <div key={chunk.key} className="chunk">
          <div className="chunk-head">
            <span className="chunk-seq">#{String(index).padStart(3, "0")}</span>
            <span className="chunk-tokens">[{chunk.startOffset}, {chunk.endOffset})</span>
          </div>
          <Input.TextArea rows={3} value={chunk.text} aria-label={`切块 ${index + 1}`} onChange={(event) => update(chunk.key, event.target.value)} />
          <Space wrap size={4} className="chunk-foot">
            <Button size="small" onClick={() => split(chunk)}>拆分</Button>
            <Button size="small" disabled={index === chunks.length - 1} onClick={() => merge(index)}>与下一块合并</Button>
            <Button size="small" danger onClick={() => remove(chunk.key)}>删除</Button>
          </Space>
        </div>
      ))}
      {chunks && chunks.length > 0 && (
        <div className="sticky-foot">
          <Button type="primary" loading={busy} onClick={() => void save()}>保存并重新向量化</Button>
        </div>
      )}
    </div>
  );
}
