// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from "vitest";
import { clearPending, loadPending, lookupChatRequest, recoveryAction, savePending } from "./chatRecovery.js";

afterEach(() => sessionStorage.clear());

describe("widget chat recovery", () => {
  it("remembers a pending question without a token and looks it up with GET", async () => {
    savePending({ requestId: "11111111-1111-4111-8111-111111111111", kbId: "kb", question: "年假有几天?" });
    expect(loadPending("kb")?.requestId).toBe("11111111-1111-4111-8111-111111111111");
    expect(sessionStorage.getItem("zhiku.widget.pending")).not.toMatch(/token|authorization/i);
    expect(recoveryAction("complete")).toBe("show");
    expect(recoveryAction("running")).toBe("wait");

    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      status: "complete", answer: "年假按司龄计算。", question: "年假有几天?", hidden: false,
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    const recovered = await lookupChatRequest({
      endpoint: "/api/chat",
      requestId: "11111111-1111-4111-8111-111111111111",
      fetchImpl,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const calls = fetchImpl.mock.calls as unknown as Array<[string, RequestInit]>;
    expect(calls[0]?.[0]).toBe("/api/chat/requests/11111111-1111-4111-8111-111111111111");
    expect(calls[0]?.[1].method).toBe("GET");
    expect(recovered?.answer).toBe("年假按司龄计算。");
    clearPending();
    expect(loadPending("kb")).toBeNull();
  });

  it("does not restore a missing receipt", async () => {
    const fetchImpl = vi.fn(async () => new Response("", { status: 404 }));
    expect(await lookupChatRequest({ endpoint: "https://example.test/api/chat", requestId: "missing", fetchImpl })).toBeNull();
  });
});