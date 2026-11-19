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

function parsePending(raw: unknown, kbId: string): PendingRequest | null {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return null;
  const row = raw as Record<string, unknown>;
  if (row.kbId !== kbId || typeof row.requestId !== "string" || typeof row.question !== "string") return null;
  const pending: PendingRequest = { requestId: row.requestId, kbId, question: row.question };
  if (typeof row.conversationId === "string") pending.conversationId = row.conversationId;
  return pending;
}

function readMap(s: Storage): Record<string, PendingRequest> {
  const raw = s.getItem(KEY);
  if (!raw) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
    const obj = parsed as Record<string, unknown>;
    if (typeof obj.kbId === "string" && typeof obj.requestId === "string" && typeof obj.question === "string") {
      const legacy = parsePending(obj, obj.kbId);
      return legacy ? { [legacy.kbId]: legacy } : {};
    }
    const map: Record<string, PendingRequest> = {};
    for (const [k, v] of Object.entries(obj)) {
      const item = parsePending(v, k);
      if (item) map[k] = item;
    }
    return map;
  } catch {
    return {};
  }
}

export function savePending(pending: PendingRequest): void {
  const s = store();
  if (!s) return;
  const map = readMap(s);
  map[pending.kbId] = pending;
  s.setItem(KEY, JSON.stringify(map));
}

export function loadPending(kbId: string): PendingRequest | null {
  const s = store();
  if (!s) return null;
  return readMap(s)[kbId] ?? null;
}

export function clearPending(kbId?: string): void {
  const s = store();
  if (!s) return;
  if (kbId === undefined) {
    s.removeItem(KEY);
    return;
  }
  const map = readMap(s);
  if (!(kbId in map)) return;
  delete map[kbId];
  if (Object.keys(map).length === 0) s.removeItem(KEY);
  else s.setItem(KEY, JSON.stringify(map));
}

export function recoveryAction(status: string): "load" | "wait" | "drop" {
  if (status === "complete" || status === "failed" || status === "refused" || status === "cancelled") return "load";
  if (status === "running" || status === "interrupted") return "wait"; return "drop";
}
