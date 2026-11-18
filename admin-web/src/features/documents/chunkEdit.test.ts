import { describe, expect, it } from "vitest";
import { mergeChunks, splitChunk, type EditableChunk } from "./chunkEdit";

const chunk: EditableChunk = { key: "c", text: "甲乙丙丁", startOffset: 10, endOffset: 14 };

describe("chunk editing", () => {
  it("splits inside the text and the source span", () => {
    const parts = splitChunk(chunk, 2);
    expect(parts).toEqual([
      { key: "c-a", text: "甲乙", startOffset: 10, endOffset: 12 },
      { key: "c-b", text: "丙丁", startOffset: 12, endOffset: 14 },
    ]);
    expect(splitChunk(chunk, 0)).toBeNull();
    expect(splitChunk(chunk, 4)).toBeNull();
  });

  it("merges adjacent chunks into one span", () => {
    const [left, right] = splitChunk(chunk, 2)!;
    expect(mergeChunks(left, right)).toMatchObject({ text: "甲乙丙丁", startOffset: 10, endOffset: 14 });
  });
});
