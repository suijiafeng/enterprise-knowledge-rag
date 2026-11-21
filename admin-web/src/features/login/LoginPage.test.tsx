import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { demoLoginAllowed, LoginPage } from "./LoginPage";

describe("login page", () => {
  const html = renderToStaticMarkup(<LoginPage onLogin={async () => undefined} onBrowseSamples={() => {}} />);

  it("signs in with username and password, no pasted tokens", () => {
    expect(html).toContain("用户名");
    expect(html).toContain('type="password"');
    expect(html).toContain("current-password");
    expect(html).not.toContain("开发令牌");
    expect(html).not.toContain("dev-token");
  });

  it("does not promise login methods that do not exist", () => {
    expect(html).not.toContain("记住我");
    expect(html).not.toContain("忘记密码");
    expect(html).toContain("密码由 IT 管理员重置");
    expect(html).not.toMatch(/<button[^>]*>[^<]*企业身份登录/);
  });

  it("does not advertise capabilities the system lacks", () => {
    for (const claim of ["混合检索", "Rerank", "92.4%", "BM25"]) expect(html).not.toContain(claim);
    expect(html).toContain("授权范围");
    expect(html).toContain("以示例数据浏览");
  });

  it("keeps the decoration out of the accessibility tree", () => {
    expect(html).toMatch(/<div class="zk-sky" aria-hidden="true">/);
    expect(html).toMatch(/<svg class="zk-mark"[^>]*aria-hidden="true"/);
    expect(html).toContain('aria-label="显示密码"');
  });

  it("lists demo accounts, collapsed, only when the server says they exist", () => {
    expect(html).not.toContain("演示账号");
    const demo = renderToStaticMarkup(<LoginPage onLogin={async () => undefined} onBrowseSamples={() => {}}
      demo={{ usernames: ["admin", "employee"], password: "fictional-demo" }} />);
    expect(demo).toContain("演示账号");
    expect(demo).toContain("仅本地开发");
    expect(demo).toMatch(/<details class="zk-demo">/);
    expect(demo).toContain("employee");
  });

  it("never offers demo accounts in a production build unless explicitly enabled", () => {
    expect(demoLoginAllowed({ DEV: false })).toBe(false);
    expect(demoLoginAllowed({ DEV: false, VITE_ZHIKU_DEMO_LOGIN: "1" })).toBe(false);
    expect(demoLoginAllowed({ DEV: false, VITE_ZHIKU_DEMO_LOGIN: "true" })).toBe(true);
    expect(demoLoginAllowed({ DEV: true })).toBe(true);
  });
});
