import { Button, Select, Space, Tag } from "antd";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ApiError, createAuthApi, createClient, type ApiClient, type AuthAccount, type Fetch, type KnowledgeBase, type Status, type TokenResponse } from "./api/client";

export interface Session {
  client: ApiClient | undefined;
  account: AuthAccount | undefined;
  /** 页面打开时正在用刷新 cookie 恢复登录。 */
  restoring: boolean;
  knowledgeBases: KnowledgeBase[];
  kbId: string | undefined;
  setKbId: (id: string) => void;
  status: Status | undefined;
  statusError: string | undefined;
  login: (username: string, password: string) => Promise<LoginFailure | undefined>;
  logout: () => void;
  /** 已登录的会话续期失败（被撤销、过期或在别处退出）后回到登录页时为 true，用来提示一次原因。 */
  sessionLost: boolean;
  acknowledgeSessionLost: () => void;
}

/** 登录失败：给用户看的原因；被限流时带上服务端 Retry-After 的秒数。 */
export interface LoginFailure {
  message: string;
  retryAfter?: number;
}

const disconnected: Session = {
  client: undefined, account: undefined, restoring: false, knowledgeBases: [], kbId: undefined, setKbId: () => {},
  status: undefined, statusError: undefined, login: async () => ({ message: "未初始化" }), logout: () => {},
  sessionLost: false, acknowledgeSessionLost: () => {},
};

const SessionContext = createContext<Session>(disconnected);
export const useSession = () => useContext(SessionContext);

const describe = (e: unknown) => {
  if (!(e instanceof ApiError)) return "无法连接服务";
  if (e.code === "INVALID_CREDENTIALS") return "用户名或密码不正确";
  if (e.code === "RATE_LIMITED") return "尝试次数过多，请稍后再试";
  if (e.status === 401) return "登录已失效，请重新登录";
  return `${e.code}${e.requestId ? ` · ${e.requestId}` : ""}`;
};

/** 提前一分钟续期，避免请求撞上过期。 */
const RENEW_EARLY_MS = 60_000;

/**
 * 登录状态。access token 只在内存（ref）里；刷新令牌是 HttpOnly cookie，页面读不到。
 * 打开页面时先静默刷新一次以恢复登录；续期失败或被撤销就回到登录页。
 */
export function SessionProvider({ children, fetchImpl, restore = true }: { children: ReactNode; fetchImpl?: Fetch; restore?: boolean }) {
  const [client, setClient] = useState<ApiClient>();
  const [account, setAccount] = useState<AuthAccount>();
  const [restoring, setRestoring] = useState(restore);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [kbId, setKbId] = useState<string>();
  const [status, setStatus] = useState<Status>();
  const [statusError, setStatusError] = useState<string>();
  const [sessionLost, setSessionLost] = useState(false);
  const token = useRef<string | undefined>(undefined);
  const inflight = useRef<Promise<string | undefined> | undefined>(undefined);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const auth = useMemo(() => createAuthApi(fetchImpl), [fetchImpl]);

  const clear = useCallback(() => {
    token.current = undefined;
    if (timer.current) clearTimeout(timer.current);
    setClient(undefined);
    setAccount(undefined);
    setKnowledgeBases([]);
    setKbId(undefined);
  }, []);

  const renewRef = useRef<() => Promise<string | undefined>>(async () => undefined);
  const accept = useCallback((res: TokenResponse) => {
    token.current = res.accessToken;
    setAccount(res.account);
    if (timer.current) clearTimeout(timer.current);
    const wait = new Date(res.expiresAt).getTime() - Date.now() - RENEW_EARLY_MS;
    if (Number.isFinite(wait) && wait > 0) timer.current = setTimeout(() => void renewRef.current(), wait);
  }, []);

  /** 同一时间只发一个刷新请求：多个 401 共用结果，避免轮换中的刷新令牌被重放。 */
  const renew = useCallback((): Promise<string | undefined> => {
    inflight.current ??= auth.refresh().then((res) => { accept(res); return res.accessToken; }, () => {
      if (token.current) setSessionLost(true);
      clear();
      return undefined;
    })
      .finally(() => { inflight.current = undefined; });
    return inflight.current;
  }, [auth, accept, clear]);
  renewRef.current = renew;

  const start = useCallback(async (res: TokenResponse) => {
    accept(res);
    const next = createClient({ current: () => token.current, refresh: renew }, fetchImpl);
    const kbs = await next.knowledgeBases();
    setClient(next);
    setKnowledgeBases(kbs);
    setKbId(kbs[0]?.id);
  }, [accept, renew, fetchImpl]);

  useEffect(() => {
    createClient(undefined, fetchImpl).status().then(setStatus, (e: unknown) => setStatusError(describe(e)));
  }, [fetchImpl]);

  useEffect(() => {
    if (!restore) return;
    let live = true;
    auth.refresh().then((res) => (live ? start(res) : undefined)).catch(() => undefined).finally(() => { if (live) setRestoring(false); });
    return () => { live = false; };
  }, [auth, start, restore]);

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  const login = useCallback(async (username: string, password: string) => {
    try {
      await start(await auth.login(username.trim(), password));
      setSessionLost(false);
      return undefined;
    } catch (e) {
      clear();
      const failure: LoginFailure = { message: describe(e) };
      if (e instanceof ApiError && e.status === 429) failure.retryAfter = e.retryAfter ?? 30;
      return failure;
    }
  }, [auth, start, clear]);

  const logout = useCallback(() => {
    void auth.logout().catch(() => undefined);
    clear();
  }, [auth, clear]);

  const acknowledgeSessionLost = useCallback(() => setSessionLost(false), []);
  const value = useMemo<Session>(() => ({
    client, account, restoring, knowledgeBases, kbId, setKbId, status, statusError, login, logout, sessionLost, acknowledgeSessionLost,
  }), [client, account, restoring, knowledgeBases, kbId, status, statusError, login, logout, sessionLost, acknowledgeSessionLost]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function ConnectControl({ onLogin }: { onLogin: () => void }) {
  const { client, account, knowledgeBases, kbId, setKbId, logout } = useSession();
  if (client) {
    return (
      <Space>
        <Tag title={account?.username}>{account ? (account.displayName ?? account.username) : "已登录"}</Tag>
        <Select
          size="small"
          className="kb-select"
          {...(kbId ? { value: kbId } : {})}
          placeholder="无已授权知识库"
          onChange={setKbId}
          options={knowledgeBases.map((k) => ({ value: k.id, label: k.name }))}
          aria-label="选择知识库"
        />
        <Button size="small" onClick={() => { logout(); onLogin(); }}>退出登录</Button>
      </Space>
    );
  }
  return (
    <>
      <Tag>示例数据</Tag>
      <Button size="small" type="primary" onClick={onLogin}>登录</Button>
    </>
  );
}
