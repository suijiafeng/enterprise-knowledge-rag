/** 待确认的提问。只放 sessionStorage，不放令牌。 */
export interface PendingRequest {
  requestId: string;
  kbId: string;
  question: string;
  conversationId?: string;
}

const KEY = "zhiku.chat.pending";

function store(): Storage | undefined {
  try {
    return globalThis.sessionStorage;
  } catch {
    return undefined;
  }
}

export function savePending(pending: PendingRequest): void {
  store()?.setItem(KEY, JSON.stringify(pending));
}

export function loadPending(kbId: string): PendingRequest | null {
  const raw = store()?.getItem(KEY);
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return null;
    const row = parsed as Record<string, unknown>;
    if (row.kbId !== kbId || typeof row.requestId !== "string" || typeof row.question !== "string") return null;
    const pending: PendingRequest = { requestId: row.requestId, kbId, question: row.question };
    if (typeof row.conversationId === "string") pending.conversationId = row.conversationId;
    return pending;
  } catch {
    return null;
  }
}

export function clearPending(): void {
  store()?.removeItem(KEY);
}

/** 恢复只查询，不重新 POST。complete 直接展示；仍在生成则继续等待。 */
export function recoveryAction(status: string): "load" | "wait" | "drop" {
  if (status === "complete" || status === "failed" || status === "refused" || status === "cancelled") return "load";
  if (status === "running" || status === "interrupted") return "wait";
  return "drop";
}
