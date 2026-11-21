// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { SessionProvider, useSession } from "./session";
import type { Fetch } from "./api/client";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const token = (n: number) => ({
  accessToken: `access-${n}`, tokenType: "Bearer", expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
  account: { id: "u1", username: "employee", displayName: "陈一鸣（示例）", roles: ["EMPLOYEE"] },
});
const STATUS = { name: "知库", stage: "development", modelEnabled: false, businessTimeZone: "Asia/Shanghai", maxUploadBytes: 1, uploadFormats: [] };

type Call = { url: string; init: RequestInit };

/** 假服务端：refreshOk 控制刷新 cookie 是否有效；valid 是当前接受的 access token。 */
function server(opts: { refreshOk: boolean }) {
  const calls: Call[] = [];
  let issued = 0;
  let valid = "";
  let refreshOk = opts.refreshOk;
  const fetchImpl: Fetch = async (url, init) => {
    calls.push({ url, init });
    if (url === "/api/status") return json(STATUS);
    if (url === "/api/auth/refresh") {
      if (!refreshOk) return json({ code: "SESSION_EXPIRED", message: "x" }, 401);
      const t = token(++issued); valid = t.accessToken; return json(t);
    }
    if (url === "/api/auth/login") {
      const body = JSON.parse(String(init.body)) as { username: string; password: string };
      if (body.username === "limited") {
        return new Response(JSON.stringify({ code: "RATE_LIMITED", message: "x" }), { status: 429, headers: { "Content-Type": "application/json", "Retry-After": "42" } });
      }
      if (body.password !== "right") return json({ code: "INVALID_CREDENTIALS", message: "x" }, 401);
      refreshOk = true;
      const t = token(++issued); valid = t.accessToken; return json(t);
    }
    if (url === "/api/auth/logout") { refreshOk = false; valid = ""; return new Response(null, { status: 204 }); }
    const auth = new Headers(init.headers).get("Authorization");
    if (auth !== `Bearer ${valid}`) return json({ code: "UNAUTHENTICATED", message: "x" }, 401);
    if (url === "/api/knowledge-bases") return json([{ id: "kb1", name: "示例库" }]);
    return json({});
  };
  return { calls, fetchImpl, expire: () => { valid = "rotated-away"; }, revoke: () => { valid = "revoked"; refreshOk = false; } };
}

function Probe() {
  const s = useSession();
  const [failure, setFailure] = useState("");
  const attempt = (u: string, p: string) => void s.login(u, p).then((f) => setFailure(f ? `${f.message}|${f.retryAfter ?? ""}` : "ok"));
  return (
    <div>
      <span data-testid="failure">{failure}</span>
      <span data-testid="lost">{String(s.sessionLost)}</span>
      <button onClick={() => attempt("limited", "x")}>limited</button>
      <span data-testid="who">{s.restoring ? "restoring" : s.account?.displayName ?? "anonymous"}</span>
      <span data-testid="kbs">{s.knowledgeBases.map((k) => k.name).join(",")}</span>
      <button onClick={() => attempt("employee", "wrong")}>bad</button>
      <button onClick={() => attempt(" employee ", "right")}>good</button>
      <button onClick={() => s.logout()}>out</button>
      <button onClick={() => void s.client?.knowledgeBases().then(() => undefined, () => undefined)}>reload</button>
    </div>
  );
}

describe("session", () => {
  it("restores the login from the refresh cookie when the page opens", async () => {
    const api = server({ refreshOk: true });
    render(<SessionProvider fetchImpl={api.fetchImpl}><Probe /></SessionProvider>);
    expect(screen.getByTestId("who").textContent).toBe("restoring");
    await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("陈一鸣（示例）"));
    expect(screen.getByTestId("kbs").textContent).toBe("示例库");
    const refresh = api.calls.find((c) => c.url === "/api/auth/refresh");
    expect(refresh?.init.credentials).toBe("same-origin");
    const kb = api.calls.find((c) => c.url === "/api/knowledge-bases");
    expect(kb?.init.credentials).toBe("omit");
    expect(new Headers(kb?.init.headers).get("Authorization")).toBe("Bearer access-1");
  });

  it("logs in with a password, explains a wrong one, and never stores the token", async () => {
    const api = server({ refreshOk: false });
    render(<SessionProvider fetchImpl={api.fetchImpl}><Probe /></SessionProvider>);
    await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("anonymous"));
    fireEvent.click(screen.getByText("good"));
    await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("陈一鸣（示例）"));
    const login = api.calls.find((c) => c.url === "/api/auth/login");
    expect(JSON.parse(String(login?.init.body))).toEqual({ username: "employee", password: "right" });
    expect(JSON.stringify(localStorage)).not.toContain("access-");
    expect(JSON.stringify(sessionStorage)).not.toContain("access-");
  });

  it("refreshes once when the access token expires and retries the request", async () => {
    const api = server({ refreshOk: true });
    render(<SessionProvider fetchImpl={api.fetchImpl}><Probe /></SessionProvider>);
    await waitFor(() => expect(screen.getByTestId("kbs").textContent).toBe("示例库"));
    api.expire();
    await act(async () => {
      fireEvent.click(screen.getByText("reload"));
      fireEvent.click(screen.getByText("reload"));
    });
    await waitFor(() => expect(api.calls.filter((c) => c.url === "/api/auth/refresh")).toHaveLength(2));
    await waitFor(() => {
      const last = api.calls.filter((c) => c.url === "/api/knowledge-bases").at(-1);
      expect(new Headers(last?.init.headers).get("Authorization")).toBe("Bearer access-2");
    });
    expect(screen.getByTestId("who").textContent).toBe("陈一鸣（示例）");
  });

  it("logs out on the server without sending the bearer token", async () => {
    const api = server({ refreshOk: true });
    render(<SessionProvider fetchImpl={api.fetchImpl}><Probe /></SessionProvider>);
    await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("陈一鸣（示例）"));
    fireEvent.click(screen.getByText("out"));
    expect(screen.getByTestId("who").textContent).toBe("anonymous");
    const logout = api.calls.find((c) => c.url === "/api/auth/logout");
    expect(logout?.init.credentials).toBe("same-origin");
    expect(new Headers(logout?.init.headers).get("Authorization")).toBeNull();
  });

  it("opens on the login page when there is no session to restore", async () => {
    const api = server({ refreshOk: false });
    vi.stubGlobal("fetch", (url: string, init: RequestInit) => api.fetchImpl(url, init));
    render(<App />);
    expect(await screen.findByText("登录管理控制台")).toBeTruthy();
    expect(screen.getByLabelText("用户名")).toBeTruthy();
    expect(screen.getByLabelText("密码")).toBeTruthy();
  });

  it("shows the reason when the password is wrong", async () => {
    const api = server({ refreshOk: false });
    vi.stubGlobal("fetch", (url: string, init: RequestInit) => api.fetchImpl(url, init));
    render(<App />);
    fireEvent.change(await screen.findByLabelText("用户名"), { target: { value: "employee" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("用户名或密码不正确")).toBeTruthy();
    expect(document.querySelector(".ant-message")?.textContent).toContain("用户名或密码不正确");
  });

  it("passes the server's Retry-After through when login is rate limited", async () => {
    const api = server({ refreshOk: false });
    render(<SessionProvider fetchImpl={api.fetchImpl}><Probe /></SessionProvider>);
    await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("anonymous"));
    fireEvent.click(screen.getByText("limited"));
    await waitFor(() => expect(screen.getByTestId("failure").textContent).toBe("尝试次数过多，请稍后再试|42"));
    fireEvent.click(screen.getByText("bad"));
    await waitFor(() => expect(screen.getByTestId("failure").textContent).toBe("用户名或密码不正确|"));
  });

  it("remembers that a live session was lost, but not an explicit logout", async () => {
    const api = server({ refreshOk: true });
    render(<SessionProvider fetchImpl={api.fetchImpl}><Probe /></SessionProvider>);
    await waitFor(() => expect(screen.getByTestId("kbs").textContent).toBe("示例库"));
    api.revoke();
    fireEvent.click(screen.getByText("reload"));
    await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("anonymous"));
    expect(screen.getByTestId("lost").textContent).toBe("true");
    fireEvent.click(screen.getByText("good"));
    await waitFor(() => expect(screen.getByTestId("lost").textContent).toBe("false"));
    fireEvent.click(screen.getByText("out"));
    expect(screen.getByTestId("lost").textContent).toBe("false");
  });
});
