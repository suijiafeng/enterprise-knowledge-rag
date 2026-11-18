/** 挂件只把未完成提问的编号放进 sessionStorage。令牌仍由宿主按次提供，不写入这里。 */
export interface PendingRequest {
  requestId: string;
  kbId: string;
  question: string;
  conversationId?: string;
}

export interface RecoveredRequest {
  status: string;
  conversationId?: string;
  messageId?: string;
  question?: string;
  answer?: string;
  hidden?: boolean;
}

const KEY = "zhiku.widget.pending";

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
    if ("token" in row || "authorization" in row) return null;
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

export function recoveryAction(status: string): "show" | "wait" | "drop" {
  if (status === "complete") return "show";
  if (status === "running" || status === "interrupted") return "wait";
  return "drop";
}

/** GET 原请求。404 返回 null。不会 POST。 */
export async function lookupChatRequest(options: {
  endpoint: string;
  requestId: string;
  getAccessToken?: () => Promise<string | undefined>;
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>;
  signal?: AbortSignal;
}): Promise<RecoveredRequest | null> {
  const fetchImpl = options.fetchImpl ?? ((url: string, init: RequestInit) => fetch(url, init));
  const headers = new Headers({ Accept: "application/json" });
  const token = await options.getAccessToken?.();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const url = `${options.endpoint.replace(/\/$/, "")}/requests/${encodeURIComponent(options.requestId)}`;
  const response = await fetchImpl(url, {
    method: "GET",
    headers,
    credentials: "omit",
    cache: "no-store",
    ...(options.signal ? { signal: options.signal } : {}),
  });
  if (response.status === 404) {
    response.body?.cancel().catch(() => undefined);
    return null;
  }
  if (!response.ok) {
    response.body?.cancel().catch(() => undefined);
    throw new Error(`HTTP_${response.status}`);
  }
  return await response.json() as RecoveredRequest;
}
