import { Button, ConfigProvider, Input, type InputRef } from "antd";
import { lazy, Suspense, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useNotice } from "../../notice";
import type { LoginFailure } from "../../session";
import { BrandMark } from "./BrandMark";
import "./login.css";

/** 动态背景单独成块，登录表单先出现，背景随后淡入。 */
const LoginBackdrop = lazy(() => import("./LoginBackdrop"));

export interface DemoLogin { usernames: string[]; password: string }

/** 演示账号只在服务端返回 demoLogin（dev 配置）且当前是开发构建、或构建时显式设置 VITE_ZHIKU_DEMO_LOGIN=true 时显示。 */
export function demoLoginAllowed(env: { DEV?: boolean; VITE_ZHIKU_DEMO_LOGIN?: string | undefined } = import.meta.env) {
  return env.DEV === true || env.VITE_ZHIKU_DEMO_LOGIN === "true";
}

/** 演示账号的说明（虚构人物，见 scripts/dev-seed.sql）。 */
const PERSONAS: Record<string, { initial: string; color: string; who: string }> = {
  admin: { initial: "林", color: "#2f5bd3", who: "林知远（示例）· IT 管理员 / 知识库运营" },
  operator: { initial: "周", color: "#0e9f6e", who: "周若溪（示例）· 知识库运营" },
  employee: { initial: "陈", color: "#d97706", who: "陈一鸣（示例）· 员工，未授权任何库" },
};

const UserIcon = () => (
  <svg className="zk-field-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
    <circle cx="8" cy="5.5" r="3" /><path d="M2.5 14c.8-2.8 3-4 5.5-4s4.7 1.2 5.5 4" />
  </svg>
);
const LockIcon = () => (
  <svg className="zk-field-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
    <rect x="2.5" y="7" width="11" height="7.5" rx="1.6" /><path d="M5 7V5a3 3 0 0 1 6 0v2" />
  </svg>
);
const EyeIcon = ({ open }: { open: boolean }) => (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true">
    {open
      ? <path d="M2 2l12 12M6.5 6.6A2 2 0 0 0 9.4 9.5M4.2 4.4C2.6 5.5 1.5 8 1.5 8s2.4 4.5 6.5 4.5c1.3 0 2.4-.4 3.4-1M7 3.6c.3 0 .7-.1 1-.1 4.1 0 6.5 4.5 6.5 4.5s-.5 1-1.5 2" />
      : <><path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8 12.1 12.5 8 12.5 1.5 8 1.5 8z" /><circle cx="8" cy="8" r="2" /></>}
  </svg>
);

const LOGIN_THEME = {
  token: { colorPrimary: "#3b54e6", colorInfo: "#3b54e6", colorLink: "#3b54e6", borderRadius: 9, controlHeight: 42, controlHeightLG: 44, colorBorder: "#d8dcea", colorTextPlaceholder: "#a6acc0" },
};

/**
 * 入口页面：用户名 + 密码登录。没有“记住我”（登录状态由服务端的 HttpOnly 刷新 cookie 维持，7 天内有效、可随时退出），
 * 没有自助找回密码（由 IT 管理员重置）。结果与限流都用顶部 toast 提示；被限流时按钮按 Retry-After 倒计时。
 */
export function LoginPage({ onLogin, onBrowseSamples, demo, sessionLost = false, onSessionLostShown }: {
  onLogin: (username: string, password: string) => Promise<LoginFailure | undefined>;
  onBrowseSamples: () => void;
  demo?: DemoLogin | null | undefined;
  sessionLost?: boolean;
  onSessionLostShown?: () => void;
}) {
  const notice = useNotice();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [reveal, setReveal] = useState(false);
  const [caps, setCaps] = useState(false);
  const [busy, setBusy] = useState(false);
  const [wait, setWait] = useState(0);
  const passwordRef = useRef<InputRef>(null);
  const submitRef = useRef<HTMLButtonElement>(null);
  const [focusSubmit, setFocusSubmit] = useState(false);
  const ready = Boolean(username.trim() && password) && wait === 0;

  /** 填入演示账号后焦点落到登录按钮：要等按钮因字段填好而可用之后再聚焦。 */
  useEffect(() => {
    if (!focusSubmit || !ready) return;
    submitRef.current?.focus();
    setFocusSubmit(false);
  }, [focusSubmit, ready]);

  useEffect(() => {
    if (!sessionLost) return;
    notice.open({ key: "session-lost", type: "info", content: "登录已失效（已在其他地方退出或被管理员停用），请重新登录", duration: 4 });
    onSessionLostShown?.();
  }, [sessionLost, notice, onSessionLostShown]);

  useEffect(() => {
    if (wait <= 0) return;
    const t = setTimeout(() => setWait((n) => n - 1), 1000);
    return () => clearTimeout(t);
  }, [wait]);

  const login = async () => {
    if (!ready || busy) return;
    setBusy(true);
    const failure = await onLogin(username, password);
    setBusy(false);
    if (!failure) {
      notice.open({ key: "login-result", type: "success", content: `欢迎回来，${username.trim()}`, duration: 2 });
      return;
    }
    if (failure.retryAfter) {
      setWait(failure.retryAfter);
      notice.open({ key: "login-result", type: "error", content: `尝试次数过多，请 ${failure.retryAfter} 秒后再试`, duration: 5 });
      return;
    }
    notice.open({ key: "login-result", type: "error", content: failure.message, duration: 3 });
    passwordRef.current?.select();
  };

  const fill = (name: string) => {
    if (!demo) return;
    setUsername(name);
    setPassword(demo.password);
    setFocusSubmit(true);
  };

  const watchCaps = (e: KeyboardEvent<HTMLInputElement>) => setCaps(e.getModifierState?.("CapsLock") ?? false);

  return (
    <ConfigProvider theme={LOGIN_THEME} button={{ autoInsertSpace: false }}>
      <div className="zk-login">
        <div className="zk-sky" aria-hidden="true" />
        <Suspense fallback={null}><LoginBackdrop /></Suspense>
        <main className="zk-login-stage">
          <BrandMark />
          <div className="zk-login-brand"><b>知库</b><span>企业制度知识库 · 每句回答都有出处</span></div>
          <section className="zk-login-card" aria-labelledby="zk-login-title">
            <h1 id="zk-login-title">登录管理控制台</h1>
            <p className="zk-login-sub">使用 IT 管理员分配的账号；可查阅的知识库以授权为准。</p>
            <form onSubmit={(e) => { e.preventDefault(); void login(); }} noValidate>
              <label className="zk-label" htmlFor="login-username">用户名</label>
              <Input id="login-username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" maxLength={64}
                autoFocus placeholder="请输入用户名" prefix={<UserIcon />} />
              <label className="zk-label" htmlFor="login-password">密码</label>
              <Input id="login-password" ref={passwordRef} type={reveal ? "text" : "password"} value={password} maxLength={200}
                onChange={(e) => setPassword(e.target.value)} onKeyDown={watchCaps} onKeyUp={watchCaps} onBlur={() => setCaps(false)}
                autoComplete="current-password" placeholder="请输入密码" prefix={<LockIcon />}
                suffix={(
                  <button type="button" className="zk-eye" aria-label={reveal ? "隐藏密码" : "显示密码"} title={reveal ? "隐藏密码" : "显示密码"}
                    aria-pressed={reveal} onClick={() => setReveal((v) => !v)}>
                    <EyeIcon open={reveal} />
                  </button>
                )} />
              {caps && <div className="zk-caps" role="status">大写锁定已开启</div>}
              <div className="zk-login-row">
                <span title="登录状态由服务端维持，可随时退出">登录状态保留 7 天</span>
                <span className="zk-hint" title="系统不发送邮件；密码由 IT 管理员在「用户与权限」中重置">密码由 IT 管理员重置</span>
              </div>
              <Button ref={submitRef} type="primary" htmlType="submit" block size="large" loading={busy} disabled={!ready} className="zk-submit">
                {wait > 0 ? `${wait} 秒后可重试` : "登录"}
              </Button>
            </form>
            {demo && (
              <details className="zk-demo">
                <summary>
                  <span>演示账号<span className="zk-tag">仅本地开发</span></span>
                  <svg className="zk-chev" width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M3 4.5l3 3 3-3" /></svg>
                </summary>
                <div className="zk-demo-list">
                  {demo.usernames.map((name) => {
                    const p = PERSONAS[name];
                    return (
                      <button type="button" key={name} className="zk-demo-row" onClick={() => fill(name)} aria-label={`填入演示账号 ${name}`}>
                        <span className="zk-av" style={{ background: p?.color ?? "#6b7390" }} aria-hidden="true">{p?.initial ?? name.slice(0, 1).toUpperCase()}</span>
                        <span><b>{name}</b>{p && <small>{p.who}</small>}</span>
                        <span className="zk-fill" aria-hidden="true">填入</span>
                      </button>
                    );
                  })}
                  <div className="zk-demo-pw">点一行同时填入用户名和密码（<span className="mono">{demo.password}</span>），再按「登录」。</div>
                </div>
              </details>
            )}
          </section>
          <div className="zk-login-points"><span>授权范围内检索</span><span>逐句引用条款</span><span>只答生效版本</span></div>
          <button type="button" className="zk-samples" onClick={onBrowseSamples}>以示例数据浏览 →</button>
        </main>
        <footer className="zk-login-foot">© 2026 知库 · 页面中的人物与制度均为虚构示例</footer>
      </div>
    </ConfigProvider>
  );
}
