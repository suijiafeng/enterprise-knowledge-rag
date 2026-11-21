// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient, Conversation, HistoryMessage } from "../../api/client";
import { savePending } from "../../api/chatRecovery";
import { LiveQa } from "./LiveQa";

function clientWith(overrides: Partial<ApiClient> = {}): ApiClient {
  return {
    conversations: vi.fn(async () => []),
    messages: vi.fn(async () => []),
    // 在途请求：始终不结束
    raw: vi.fn(() => new Promise<Response>(() => {})),
    ...overrides,
  } as unknown as ApiClient;
}
afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

const conversation: Conversation = { id: "conv", kbId: "kb", title: "虚构会话", updatedAt: "2030-01-01T00:00:00Z" };
const answered: HistoryMessage = {
  id: "m1", question: "虚构问题", answer: "中性回答", hidden: false, reason: null, stale: false, citations: [],
  createdAt: "2030-01-01T00:00:00Z", feedback: null, status: "complete", errorCode: null,
};
const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });

describe("question composer", () => {
  it("does not send while an input method is composing, and sends on a plain Enter", async () => {
    const client = clientWith();
    render(<LiveQa client={client} kbId="kb" modelEnabled />);
    const input = screen.getByLabelText("问题输入") as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: "虚构问题" } });

    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(client.raw).not.toHaveBeenCalled();
    expect(input.value).toBe("虚构问题");

    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(client.raw).toHaveBeenCalledTimes(1));
  });
});

describe("answer feedback", () => {
  it("keeps the down-vote form and its input when submitting fails", async () => {
    const client = clientWith({
      conversations: vi.fn(async () => [conversation]),
      messages: vi.fn(async () => [answered]),
      rate: vi.fn(async () => { throw new Error("network"); }),
    });
    render(<LiveQa client={client} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    fireEvent.click(await screen.findByRole("button", { name: /踩/ }));
    fireEvent.change(await screen.findByLabelText("补充说明"), { target: { value: "中性说明" } });
    fireEvent.mouseDown(screen.getByLabelText("点踩原因"));
    fireEvent.click((await screen.findAllByText(/./, { selector: ".ant-select-item-option-content" }))[0]!);
    fireEvent.click(screen.getByRole("button", named("提交")));

    expect(await screen.findByText("反馈提交失败")).toBeTruthy();
    expect(client.rate).toHaveBeenCalledTimes(1);
    expect((screen.getByLabelText("补充说明") as HTMLInputElement).value).toBe("中性说明");
    // 加载图标离场动画期间仍计入按钮名称
    await waitFor(() => expect((screen.getByRole("button", { name: /提\s*交$/ }) as HTMLButtonElement).disabled).toBe(false));
  });
});

describe("conversation switching", () => {
  const second: Conversation = { ...conversation, id: "conv2", title: "另一个会话" };
  const later: HistoryMessage = { ...answered, id: "m2", question: "另一个问题", answer: "另一个回答" };

  it("clears the previous conversation at once and blocks sending until the new one loads", async () => {
    let resolve!: (m: HistoryMessage[]) => void;
    const messages = vi.fn(async (id: string) => id === "conv" ? [answered] : new Promise<HistoryMessage[]>((r) => { resolve = r; }));
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation, second]), messages })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    await screen.findByText("中性回答");

    fireEvent.click(screen.getByText("另一个会话"));
    expect(screen.queryByText("中性回答")).toBeNull();
    expect(screen.queryByText("暂无问答记录")).toBeNull();
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(true);

    resolve([later]);
    expect(await screen.findByText("另一个回答")).toBeTruthy();
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(false);
  });

  it("does not show the previous conversation when loading the next one fails", async () => {
    const messages = vi.fn(async (id: string) => { if (id === "conv") return [answered]; throw new Error("network"); });
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation, second]), messages })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    await screen.findByText("中性回答");
    fireEvent.click(screen.getByText("另一个会话"));
    expect(await screen.findByText("无法读取该会话")).toBeTruthy();
    expect(screen.queryByText("中性回答")).toBeNull();
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(false);
  });

  it("does not stay in the loading state when the knowledge base changes mid-load", async () => {
    const messages = vi.fn(() => new Promise<HistoryMessage[]>(() => {}));
    const client = clientWith({ conversations: vi.fn(async () => [conversation]), messages });
    const { rerender } = render(<LiveQa client={client} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(true);
    rerender(<LiveQa client={client} kbId="kb2" modelEnabled />);
    await waitFor(() => expect((screen.getByLabelText("问题输入") as HTMLTextAreaElement).disabled).toBe(false));
  });

  it("ignores a late history response after starting a new conversation", async () => {
    let resolve!: (m: HistoryMessage[]) => void;
    const messages = vi.fn(() => new Promise<HistoryMessage[]>((r) => { resolve = r; }));
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation]), messages })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    fireEvent.click(screen.getByRole("button", { name: /新会话/ }));
    resolve([answered]);
    expect(await screen.findByText("暂无问答记录")).toBeTruthy();
    expect(screen.queryByText("中性回答")).toBeNull();
  });
});

describe("request recovery", () => {
  it("reloads the saved answer by request id and does not post again", async () => {
    savePending({ requestId: "11111111-1111-4111-8111-111111111111", kbId: "kb", question: "虚构问题" });
    const raw = vi.fn(() => { throw new Error("must not resend"); });
    const chatRequest = vi.fn(async () => ({
      status: "complete", conversationId: "conv", messageId: "m1", acceptedAt: "2030-01-01T00:00:00Z",
      question: "虚构问题", answer: "中性回答",
    }));
    render(<LiveQa client={clientWith({
      raw, chatRequest,
      conversations: vi.fn(async () => [conversation]),
      messages: vi.fn(async () => [answered]),
    })} kbId="kb" modelEnabled />);
    expect(await screen.findByText("已按原请求编号恢复，没有重新发送。")).toBeTruthy();
    expect(await screen.findByText("中性回答")).toBeTruthy();
    expect(raw).not.toHaveBeenCalled();
    expect(chatRequest).toHaveBeenCalledWith("11111111-1111-4111-8111-111111111111");
  });

  it("pages history with a cursor and keeps both pages", async () => {
    const earlier: HistoryMessage = { ...answered, id: "m0", question: "更早的问题", answer: "更早的回答" };
    const messagePage = vi.fn(async (_id: string, query?: { before?: string }) => query?.before
      ? { items: [earlier], nextCursor: null }
      : { items: [answered], nextCursor: "cursor-1" });
    render(<LiveQa client={clientWith({
      conversations: vi.fn(async () => [conversation]),
      messagePage,
    })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    await screen.findByText("中性回答");
    fireEvent.click(screen.getByRole("button", { name: "更早的问答" }));
    expect(await screen.findByText("更早的回答")).toBeTruthy();
    expect(screen.getByText("中性回答")).toBeTruthy();
    expect(messagePage).toHaveBeenLastCalledWith("conv", { limit: 2, before: "cursor-1" });
  });
});

describe("refusals and cited sources", () => {
  it("shows a no-evidence refusal as a normal answer, without an error style or retry", async () => {
    const refused: HistoryMessage = { ...answered, id: "m3", answer: null, status: "refused", errorCode: "NO_EVIDENCE" };
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation]), messages: vi.fn(async () => [refused]) })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    expect(await screen.findByText(/资料中没有足够依据回答这个问题/)).toBeTruthy();
    expect(document.querySelector(".ant-alert")).toBeNull();
    expect(screen.queryByRole("button", { name: /重试/ })).toBeNull();
  });

  it("uses the server's refusal text in history so it matches the live answer", async () => {
    const live = "资料中没有足够依据回答这个问题。可以换个说法再问，或联系人事服务台。";
    const refused: HistoryMessage = { ...answered, id: "m5", answer: null, status: "refused", errorCode: "NO_EVIDENCE", reason: live };
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation]), messages: vi.fn(async () => [refused]) })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    expect(await screen.findByText(live)).toBeTruthy();
  });

  it("keeps the composer outside the scrolling thread so it stays at the bottom", async () => {
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => []) })} kbId="kb" modelEnabled />);
    const input = await screen.findByLabelText("问题输入");
    const thread = document.querySelector(".qa-thread");
    expect(thread).toBeTruthy();
    expect(thread?.contains(input)).toBe(false);
    expect(input.closest(".composer")?.parentElement?.classList.contains("ant-card-body")).toBe(true);
  });

  it("lists only the sources the answer cited, keeping their numbers, and shows the support check", async () => {
    const withSources: HistoryMessage = {
      ...answered, id: "m4", answer: "依据第二条[2]。",
      citations: [
        { documentId: "d1", versionId: "v1", title: "未引用的资料", excerpt: "甲", index: 1, cited: false },
        { documentId: "d2", versionId: "v2", title: "被引用的资料", excerpt: "乙", index: 2, cited: true },
      ],
      support: { checked: 1, supported: 1, weak: [] },
    };
    render(<LiveQa client={clientWith({ conversations: vi.fn(async () => [conversation]), messages: vi.fn(async () => [withSources]) })} kbId="kb" modelEnabled />);
    fireEvent.click(await screen.findByText("虚构会话"));
    fireEvent.click(await screen.findByText(/依据第二条/));
    expect(await screen.findByText("被引用的资料")).toBeTruthy();
    expect(screen.queryByText("未引用的资料")).toBeNull();
    expect(screen.getByText("#2")).toBeTruthy();
    expect(screen.getByText(/引用核对：1\/1 句/)).toBeTruthy();
  });
});
