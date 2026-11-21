import { Button, Divider, Input, Typography } from "antd";
import { useState } from "react";

const FACTS = [
  { title: "授权范围内检索", body: "先按知识库授权与制度效力筛选版本，再做向量检索" },
  { title: "引用可核对", body: "每个回答标注来源版本，提交前检查引用编号" },
  { title: "撤权即时生效", body: "撤销授权后，检索、引用与历史回答一并停止披露" },
];

/**
 * 入口页面：用户名 + 密码登录。没有“记住我”（登录状态由服务端的 HttpOnly 刷新 cookie 维持，7 天内有效、可随时退出），
 * 没有自助找回密码（由 IT 管理员重置）。演示账号只在服务端开启 dev 配置时显示。
 */
export function LoginPage({ onLogin, onBrowseSamples, demo }: {
  onLogin: (username: string, password: string) => Promise<string | undefined>;
  onBrowseSamples: () => void;
  demo?: { usernames: string[]; password: string } | null | undefined;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const ready = Boolean(username.trim() && password);

  const login = async () => {
    if (!ready || busy) return;
    setBusy(true);
    const failure = await onLogin(username, password);
    setBusy(false);
    setError(failure);
    if (!failure) setPassword("");
  };

  const fill = (name: string) => {
    if (!demo) return;
    setUsername(name);
    setPassword(demo.password);
    setError(undefined);
  };

  return (
    <div className="login">
      <aside className="login-brand">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">知</span>
          <div>
            <div className="login-title">知库 RAG</div>
            <div className="brand-sub">企业知识库问答系统</div>
          </div>
        </div>
        <ul className="login-facts">
          {FACTS.map((f) => (
            <li key={f.title}>
              <span className="login-check" aria-hidden="true">✓</span>
              <div>
                <div className="brand-name">{f.title}</div>
                <div className="brand-sub">{f.body}</div>
              </div>
            </li>
          ))}
        </ul>
        <div className="brand-sub">开发环境 · 尚未接入生产服务</div>
      </aside>
      <main className="login-main">
        <form className="login-card" aria-label="登录" onSubmit={(e) => { e.preventDefault(); void login(); }}>
          <Typography.Title level={3} className="page-title">登录管理控制台</Typography.Title>
          <Typography.Text type="secondary">使用 IT 管理员分配的账号登录；角色与知识库授权以服务端为准。</Typography.Text>
          <label className="login-label" htmlFor="login-username">用户名</label>
          <Input id="login-username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" maxLength={64} autoFocus />
          <label className="login-label" htmlFor="login-password">密码</label>
          <Input.Password id="login-password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" maxLength={200} />
          {error && <Typography.Text type="danger" role="alert">{error}</Typography.Text>}
          <Button type="primary" htmlType="submit" block size="large" loading={busy} disabled={!ready}>登录</Button>
          {demo && (
            <>
              <Divider plain>演示账号（仅本地开发）</Divider>
              <div className="login-demo">
                {demo.usernames.map((name) => <Button key={name} size="small" onClick={() => fill(name)}>{name}</Button>)}
              </div>
              <Typography.Text type="secondary" className="page-sub">点选即可填入；密码为 <span className="mono">{demo.password}</span>。</Typography.Text>
            </>
          )}
          <Button type="link" onClick={onBrowseSamples}>以示例数据浏览</Button>
        </form>
      </main>
    </div>
  );
}
