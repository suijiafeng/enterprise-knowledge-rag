// @vitest-environment happy-dom
import { App as AntApp } from "antd";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LoginResult, SessionLost } from "../../session";
import { LoginPage } from "./LoginPage";

afterEach(() => { cleanup(); vi.useRealTimers(); document.body.innerHTML = ""; });

const DEMO = { usernames: ["admin", "operator", "employee"], password: "fictional-demo" };

const OK = async (): Promise<LoginResult> => ({ ok: true, name: "林知远（示例）" });

function setup(result: () => Promise<LoginResult>, extra: { sessionLost?: SessionLost; onSessionLostShown?: () => void } = {}) {
  const onLogin = vi.fn(result);
  render(<AntApp><LoginPage onLogin={onLogin} onBrowseSamples={() => {}} demo={DEMO} {...extra} /></AntApp>);
  const user = screen.getByLabelText("用户名") as HTMLInputElement;
  const password = screen.getByLabelText("密码") as HTMLInputElement;
  const submit = () => screen.getByRole("button", { name: /登录|秒后可重试/ }) as HTMLButtonElement;
  return { onLogin, user, password, submit };
}

const type = (user: HTMLInputElement, password: HTMLInputElement, u: string, p: string) => {
  fireEvent.change(user, { target: { value: u } });
  fireEvent.change(password, { target: { value: p } });
};

describe("login page interactions", () => {
  it("enables the full-width button only when both fields are filled", () => {
    const { user, password, submit } = setup(OK);
    expect(submit().disabled).toBe(true);
    fireEvent.change(user, { target: { value: "admin" } });
    expect(submit().disabled).toBe(true);
    fireEvent.change(password, { target: { value: "x" } });
    expect(submit().disabled).toBe(false);
    expect(submit().className).toContain("ant-btn-block");
  });

  it("explains a wrong password in a toast, not inline", async () => {
    const { user, password, submit, onLogin } = setup(async () => ({ message: "用户名或密码不正确" }));
    type(user, password, " admin ", "wrong");
    fireEvent.click(submit());
    expect(await screen.findByText("用户名或密码不正确")).toBeTruthy();
    expect(onLogin).toHaveBeenCalledWith(" admin ", "wrong");
    expect(document.querySelector(".ant-message")).not.toBeNull();
  });

  it("counts down from Retry-After on the button when rate limited", async () => {
    const { user, password, submit } = setup(async () => ({ message: "尝试次数过多，请稍后再试", retryAfter: 3 }));
    type(user, password, "admin", "wrong");
    vi.useFakeTimers();
    await act(async () => { fireEvent.click(submit()); });
    expect(submit().textContent).toBe("3 秒后可重试");
    expect(submit().disabled).toBe(true);
    expect(screen.getByText("尝试次数过多，请 3 秒后再试")).toBeTruthy();
    act(() => { vi.advanceTimersByTime(1000); });
    expect(submit().textContent).toBe("2 秒后可重试");
    act(() => { vi.advanceTimersByTime(1000); });
    act(() => { vi.advanceTimersByTime(1000); });
    expect(submit().textContent).toBe("登录");
    expect(submit().disabled).toBe(false);
  });

  it("welcomes the user back after a successful login", async () => {
    const { user, password, submit } = setup(OK);
    type(user, password, "admin", "right");
    fireEvent.click(submit());
    expect(await screen.findByText("欢迎回来，林知远（示例）")).toBeTruthy();
  });

  it("reveals the password with the eye button inside the field", () => {
    const { password } = setup(OK);
    const eye = screen.getByRole("button", { name: "显示密码" });
    expect(password.closest(".ant-input-affix-wrapper")?.contains(eye)).toBe(true);
    fireEvent.click(eye);
    expect(password.type).toBe("text");
    expect(screen.getByRole("button", { name: "隐藏密码" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("warns when caps lock is on while typing the password", () => {
    const { password } = setup(OK);
    const press = (on: boolean) => act(() => {
      const ev = new KeyboardEvent("keyup", { bubbles: true, key: "A" });
      Object.defineProperty(ev, "getModifierState", { value: (k: string) => on && k === "CapsLock" });
      password.dispatchEvent(ev);
    });
    press(true);
    expect(screen.getByText("大写锁定已开启")).toBeTruthy();
    press(false);
    expect(screen.queryByText("大写锁定已开启")).toBeNull();
  });

  it("fills a demo account in one click and moves focus to the button", () => {
    const { user, password, submit } = setup(OK);
    const details = document.querySelector("details.zk-demo") as HTMLDetailsElement;
    expect(details.open).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "填入演示账号 operator" }));
    expect(user.value).toBe("operator");
    expect(password.value).toBe("fictional-demo");
    expect(document.activeElement).toBe(submit());
  });

  it("says once why the user was signed out", async () => {
    const shown = vi.fn();
    setup(OK, { sessionLost: "revoked", onSessionLostShown: shown });
    await waitFor(() => expect(screen.getByText(/登录已在其他地方退出或被管理员撤销/)).toBeTruthy());
    expect(shown).toHaveBeenCalledTimes(1);
  });

  it("tells a disabled account apart from an expired login", async () => {
    setup(OK, { sessionLost: "disabled" });
    await waitFor(() => expect(screen.getByText(/账号已被管理员停用/)).toBeTruthy());
    cleanup();
    document.body.innerHTML = "";
    setup(OK, { sessionLost: "expired" });
    await waitFor(() => expect(screen.getByText("登录已过期，请重新登录")).toBeTruthy());
  });
});
