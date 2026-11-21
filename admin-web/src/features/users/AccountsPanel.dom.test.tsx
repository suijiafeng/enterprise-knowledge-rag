// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AccountInfo, ApiClient } from "../../api/client";
import { AccountsPanel } from "./AccountsPanel";

afterEach(() => { cleanup(); document.body.innerHTML = ""; });

const SELF = "00000000-0000-4000-8000-0000000000a1";
const accounts: AccountInfo[] = [
  { id: SELF, username: "admin", displayName: "林知远（示例）", roles: ["IT_ADMIN"], disabled: false, activeSessions: 1, mustChangePassword: false },
  { id: "b2", username: "chen", displayName: "陈一鸣（示例）", roles: ["EMPLOYEE"], disabled: false, activeSessions: 2, mustChangePassword: true },
  { id: "c3", username: "old", displayName: null, roles: ["EMPLOYEE"], disabled: true, activeSessions: 0, mustChangePassword: false },
];

function clientWith(overrides: Partial<Record<keyof ApiClient, unknown>> = {}) {
  return {
    accounts: vi.fn(async () => accounts),
    createAccount: vi.fn(async () => ({ id: "n", username: "lin.xiao", temporaryPassword: "TempPassw0rdXyz9" })),
    updateAccount: vi.fn(async () => {}),
    resetPassword: vi.fn(async () => ({ temporaryPassword: "ResetPassw0rd234" })),
    revokeSessions: vi.fn(async () => {}),
    ...overrides,
  } as unknown as ApiClient & Record<string, ReturnType<typeof vi.fn>>;
}

const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });
const row = async (text: string) => (await screen.findByText(text)).closest("tr") as HTMLElement;

describe("accounts panel", () => {
  it("lists accounts with roles and state, and never offers actions on your own account", async () => {
    render(<AccountsPanel client={clientWith()} selfId={SELF} />);
    const self = await row("林知远（示例）");
    expect(within(self).getByText("当前账号")).toBeTruthy();
    expect(within(self).queryByRole("button")).toBeNull();
    const chen = await row("陈一鸣（示例）");
    expect(within(chen).getByText("待改密")).toBeTruthy();
    expect(within(chen).getByRole("button", named("下线"))).toBeTruthy();
    const old = await row("old");
    expect(within(old).getByText("已停用")).toBeTruthy();
    expect(within(old).getByRole("button", named("启用"))).toBeTruthy();
    expect(within(old).queryByRole("button", named("下线"))).toBeNull();
  });

  it("creates an account and shows the one-time password once", async () => {
    const client = clientWith();
    render(<AccountsPanel client={client} selfId={SELF} />);
    fireEvent.click(await screen.findByRole("button", named("新建账号")));
    fireEvent.change(await screen.findByPlaceholderText("如：lin.xiao"), { target: { value: "Lin.Xiao" } });
    fireEvent.change(screen.getByPlaceholderText("如：林晓"), { target: { value: "林晓" } });
    fireEvent.click(screen.getByRole("button", named("创建")));
    await waitFor(() => expect(client.createAccount).toHaveBeenCalledWith({ username: "lin.xiao", displayName: "林晓", roles: ["EMPLOYEE"] }));
    expect(await screen.findByText("账号已创建")).toBeTruthy();
    expect(screen.getByText("TempPassw0rdXyz9")).toBeTruthy();
    await waitFor(() => expect(client.accounts).toHaveBeenCalledTimes(2));
  });

  it("resets a password only after confirmation and disables with a confirmation", async () => {
    const client = clientWith();
    render(<AccountsPanel client={client} selfId={SELF} />);
    const chen = await row("陈一鸣（示例）");
    fireEvent.click(within(chen).getByRole("button", named("重置密码")));
    expect(client.resetPassword).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", named("重置")));
    await waitFor(() => expect(client.resetPassword).toHaveBeenCalledWith("b2"));
    expect(await screen.findByText("ResetPassw0rd234")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", named("我已转交")));

    fireEvent.click(within(await row("陈一鸣（示例）")).getByRole("button", named("停用")));
    await waitFor(() => expect(screen.getAllByRole("button", named("停用"))).toHaveLength(2));
    fireEvent.click(screen.getAllByRole("button", named("停用")).at(-1)!);
    await waitFor(() => expect(client.updateAccount).toHaveBeenCalledWith("b2", { disabled: true }));
  });

  it("changes roles from a small dialog", async () => {
    const client = clientWith();
    render(<AccountsPanel client={client} selfId={SELF} />);
    fireEvent.click(within(await row("陈一鸣（示例）")).getByRole("button", named("角色")));
    fireEvent.click(await screen.findByRole("checkbox", { name: "知识库运营" }));
    fireEvent.click(screen.getByRole("button", named("保存")));
    await waitFor(() => expect(client.updateAccount).toHaveBeenCalledWith("b2", { roles: ["EMPLOYEE", "KB_OPERATOR"] }));
  });
});
