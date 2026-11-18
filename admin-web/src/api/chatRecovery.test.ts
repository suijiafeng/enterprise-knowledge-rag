// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from "vitest";
import { clearPending, loadPending, recoveryAction, savePending } from "./chatRecovery";

afterEach(() => sessionStorage.clear());

describe("chat recovery", () => {
  it("stores the request id for the same knowledge base and never a token", () => {
    savePending({ requestId: "11111111-1111-4111-8111-111111111111", kbId: "kb", question: "年假有几天?", conversationId: "conv" });
    expect(loadPending("kb")).toEqual({
      requestId: "11111111-1111-4111-8111-111111111111",
      kbId: "kb",
      question: "年假有几天?",
      conversationId: "conv",
    });
    expect(loadPending("other")).toBeNull();
    expect(sessionStorage.getItem("zhiku.chat.pending")).not.toMatch(/token|authorization/i);
    clearPending();
    expect(loadPending("kb")).toBeNull();
  });

  it("loads a finished request and waits on one that is still running, without resending", () => {
    expect(recoveryAction("complete")).toBe("load");
    expect(recoveryAction("failed")).toBe("load");
    expect(recoveryAction("running")).toBe("wait");
    expect(recoveryAction("interrupted")).toBe("wait");
    expect(recoveryAction("unknown")).toBe("drop");
    expect(Object.values({ complete: recoveryAction("complete"), running: recoveryAction("running") })).not.toContain("resend");
  });
});
