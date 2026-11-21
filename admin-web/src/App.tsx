import { App as AntApp, Breadcrumb, Button, ConfigProvider, Layout, Menu, Spin } from "antd";
import { Suspense, useState } from "react";
import { ConnectControl, SessionProvider, useSession } from "./session";
import { LoginPage } from "./features/login/LoginPage";
import { NAV, NEEDS_KB, type NavKey } from "./nav";
import { LAZY_ROUTES } from "./routes";
import { NoKnowledgeBase } from "./NoKnowledgeBase";

export type { NavKey, PageContext } from "./nav";

type Mode = "login" | "sample";

export function App({ initialMode = "login" }: { initialMode?: Mode }) {
  return (
    <ConfigProvider button={{ autoInsertSpace: false }} theme={{
      token: {
        colorPrimary: "#2f5bd3",
        colorInfo: "#2f5bd3",
        colorLink: "#2f5bd3",
        colorSuccess: "#1f9d55",
        colorWarning: "#d97706",
        colorError: "#d4380d",
        colorBgLayout: "#f4f6f9",
        colorBorder: "#e6e9ef",
        colorBorderSecondary: "#e6e9ef",
        colorText: "#1d2433",
        colorTextSecondary: "#6b7385",
        borderRadius: 6,
        borderRadiusLG: 8,
        fontSize: 14,
        fontFamily: 'ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Noto Sans SC", "Microsoft YaHei", sans-serif',
        boxShadow: "0 1px 2px rgba(0,0,0,0.04)",
        boxShadowSecondary: "0 1px 2px rgba(0,0,0,0.04)",
      },
      components: {
        Button: { primaryShadow: "none", defaultShadow: "none", dangerShadow: "none" },
        Layout: { bodyBg: "#f4f6f9", headerBg: "#FFFFFF", siderBg: "#1f2638" },
        Menu: { itemBorderRadius: 6, itemSelectedBg: "#2f5bd3", itemSelectedColor: "#ffffff", itemHoverBg: "#283049", itemColor: "#c5cad6", darkItemBg: "#1f2638", darkItemSelectedBg: "#2f5bd3", darkItemSelectedColor: "#ffffff", darkItemHoverBg: "#283049", darkItemColor: "#c5cad6", activeBarBorderWidth: 0 },
      },
    }}>
      <AntApp message={{ top: 64, maxCount: 3, duration: 3 }}>
        <SessionProvider>
          <Entry initialMode={initialMode} />
        </SessionProvider>
      </AntApp>
    </ConfigProvider>
  );
}

/** 先登录；会话连接后或用户选择示例数据时才显示控制台。 */
function Entry({ initialMode }: { initialMode: Mode }) {
  const session = useSession();
  const [mode, setMode] = useState<Mode>(initialMode);
  if (!session.client && mode === "login") {
    return <LoginPage onLogin={session.connect} onBrowseSamples={() => setMode("sample")} />;
  }
  return <Shell onLogin={() => setMode("login")} />;
}

/** 开发环境提示：一行小字，可关闭；不再是整块黄色横幅。 */
export function DevBanner({ connected }: { connected: boolean }) {
  const [open, setOpen] = useState(true);
  if (!open) return null;
  return (
    <div className="dev-banner" role="note">
      <span className="dev-dot" aria-hidden="true" />
      <span>
        开发环境，尚未接入生产服务 · {connected ? "本地数据" : "示例数据"} · 回答的 validated 只是 metadata-only 来源检查，不代表回答正确
      </span>
      <Button type="text" size="small" className="dev-banner-close" aria-label="关闭提示" onClick={() => setOpen(false)}>×</Button>
    </div>
  );
}

/** 管理端外壳：侧栏通高，内容区单独滚动；页面按路由懒加载。 */
function Shell({ onLogin }: { onLogin: () => void }) {
  const session = useSession();
  const [active, setActive] = useState<NavKey>("overview");
  const kbName = session.knowledgeBases.find((k) => k.id === session.kbId)?.name;
  const current = NAV.find((n) => n.key === active) ?? NAV[0];
  const Page = LAZY_ROUTES[active];
  const blocked = session.client && !session.kbId && NEEDS_KB.includes(active);

  return (
    <Layout className="shell">
      <Layout.Sider theme="dark" width={208} className="shell-sider" breakpoint="lg" collapsedWidth={0}>
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">知</span>
          <div>
            <div className="brand-name">知库 RAG</div>
            <div className="brand-sub">企业知识库问答</div>
          </div>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          className="shell-menu"
          selectedKeys={[active]}
          onClick={({ key }) => setActive(key as NavKey)}
          items={NAV.map((n) => ({ key: n.key, label: n.label }))}
        />
        <div className="shell-user">
          <div>
            <div className="brand-name">{session.client ? "开发令牌" : "未连接"}</div>
            <div className="brand-sub">{session.client ? "本地 JWT，生产需 IdP" : "示例数据"}</div>
          </div>
        </div>
      </Layout.Sider>
      <Layout className="shell-main">
        <Layout.Header className="shell-header">
          <Breadcrumb items={[{ title: kbName ?? "知识库" }, { title: current.label }]} />
          <div className="shell-tools">
            <ConnectControl onLogin={onLogin} />
          </div>
        </Layout.Header>
        <Layout.Content className={active === "qa" ? "shell-content shell-content-qa" : "shell-content"}>
          <DevBanner connected={Boolean(session.client)} />
          {blocked ? <NoKnowledgeBase /> : (
            <Suspense fallback={<div className="route-loading"><Spin /></div>}>
              <Page client={session.client} kbId={session.kbId} kbName={kbName} status={session.status} statusError={session.statusError} navigate={setActive} />
            </Suspense>
          )}
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
