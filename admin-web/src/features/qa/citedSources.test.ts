import { describe, expect, it } from "vitest";
import { citedSources } from "./chatState";

describe("citedSources", () => {
  const base = { documentId: "d", versionId: "v", title: "t", excerpt: "e" };
  it("keeps only cited sources with their original numbers", () => {
    expect(citedSources([{ ...base, index: 1, cited: false }, { ...base, index: 2, cited: true }]).map((c) => c.index)).toEqual([2]);
  });
  it("treats sources from an older server (no flags) as cited, numbered by position", () => {
    expect(citedSources([base, base]).map((c) => c.index)).toEqual([1, 2]);
  });
});
