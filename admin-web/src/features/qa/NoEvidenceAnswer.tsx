import { Tag } from "antd";

export const NO_EVIDENCE_TEXT = "资料中没有足够依据回答这个问题。可以换个说法再问，或联系人工渠道。";

/** "资料不足"是正常回答，不是故障：按普通回答排版，没有错误色、没有重试按钮。 */
export function NoEvidenceAnswer({ message }: { message?: string | undefined }) {
  return (
    <div className="no-evidence" role="status">
      <p className="answer-text">{message || NO_EVIDENCE_TEXT}</p>
      <div className="answer-foot">
        <Tag>未找到可引用的依据</Tag>
      </div>
    </div>
  );
}

/** 词面引用支撑检查结果；只是提示，不代表语义验证。 */
export function SupportTag({ support }: { support: { checked: number; supported: number; weak: number[] } }) {
  if (support.checked === 0) return null;
  if (support.weak.length === 0) return <Tag color="green">引用核对：{support.supported}/{support.checked} 句能在来源中找到原文</Tag>;
  return (
    <Tag color="orange" title="按字面比对：这些句子在所引来源里找不到相近原文，请人工核对">
      {support.weak.length} 句在所引来源中找不到相近原文，请核对
    </Tag>
  );
}
