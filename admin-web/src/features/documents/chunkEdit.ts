import type { ChunkDraft, ChunkView } from "../../api/client";

export interface EditableChunk extends ChunkDraft {
  key: string;
}

export function fromServer(chunks: ChunkView[]): EditableChunk[] {
  return chunks.map((chunk) => ({
    key: chunk.id,
    text: chunk.text,
    startOffset: chunk.startOffset,
    endOffset: chunk.endOffset,
  }));
}

/** 在正文下标处拆开，并把源文偏移按字数比例切开。切点必须落在正文中间。 */
export function splitChunk(chunk: EditableChunk, at: number): [EditableChunk, EditableChunk] | null {
  if (at <= 0 || at >= chunk.text.length) return null;
  const span = chunk.endOffset - chunk.startOffset;
  const mid = chunk.startOffset + Math.max(1, Math.min(span - 1, Math.round(span * (at / chunk.text.length))));
  if (mid <= chunk.startOffset || mid >= chunk.endOffset) return null;
  return [
    { key: `${chunk.key}-a`, text: chunk.text.slice(0, at), startOffset: chunk.startOffset, endOffset: mid },
    { key: `${chunk.key}-b`, text: chunk.text.slice(at), startOffset: mid, endOffset: chunk.endOffset },
  ];
}

export function mergeChunks(left: EditableChunk, right: EditableChunk): EditableChunk {
  return {
    key: `${left.key}+${right.key}`,
    text: `${left.text}${right.text}`,
    startOffset: Math.min(left.startOffset, right.startOffset),
    endOffset: Math.max(left.endOffset, right.endOffset),
  };
}

export function toDrafts(chunks: EditableChunk[]): ChunkDraft[] {
  return chunks.map(({ text, startOffset, endOffset }) => ({ text, startOffset, endOffset }));
}
