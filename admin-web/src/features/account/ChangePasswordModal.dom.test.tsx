// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ApiClient } from "../../api/client";
import { ChangePasswordModal } from "./ChangePasswordModal";
import { passwordProblem } from "./passwordRules";

afterEach(() => { cleanup(); document.body.innerHTML = ""; });

const named = (text: string) => ({ name: new RegExp(`^${text.split("").join("\\s*")}$`) });

function fill(current: string, next: string, confirm = next) {
  const [c, n, k] = Array.from(document.querySelectorAll<HTMLInputElement>("input[type=password]"));
  fireEvent.change(c!, { target: { value: current } });
  fireEvent.change(n!, { target: { value: next } });
  fireEvent.change(k!, { target: { value: confirm } });
}

describe("change password", () => {
  it("mirrors the server rules", () => {
    expect(passwordProblem("lin", "short")).toMatch(/10–200/);
    expect(passwordProblem("lin.xiao.2026", "LIN.XIAO.2026")).toBe("密码不能与用户名相同");
    expect(passwordProblem("lin", "aaaaaaaaaaaa")).toMatch(/过于简单/);
    expect(passwordProblem("lin", "brand-new-Secret-42")).toBeUndefined();
  });

  it("checks the confirmation, then changes the password", async () => {
    const changePassword = vi.fn(async () => {});
    const onChanged = vi.fn();
    render(<ChangePasswordModal open client={{ changePassword } as unknown as ApiClient} username="lin" onClose={() => {}} onChanged={onChanged} />);
    fill("old-Secret-123", "brand-new-Secret-42", "brand-new-Secret-43");
    fireEvent.click(screen.getByRole("button", named("修改密码")));
    expect(await screen.findByText("两次输入的新密码不一致")).toBeTruthy();
    expect(changePassword).not.toHaveBeenCalled();
    fill("old-Secret-123", "brand-new-Secret-42");
    fireEvent.click(screen.getByRole("button", named("修改密码")));
    await waitFor(() => expect(changePassword).toHaveBeenCalledWith("old-Secret-123", "brand-new-Secret-42"));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("says when the current password is wrong", async () => {
    const changePassword = vi.fn(async () => { throw new ApiError(422, "INVALID_CURRENT_PASSWORD", "x"); });
    render(<ChangePasswordModal open client={{ changePassword } as unknown as ApiClient} username="lin" onClose={() => {}} onChanged={() => {}} />);
    fill("wrong-Secret-1", "brand-new-Secret-42");
    fireEvent.click(screen.getByRole("button", named("修改密码")));
    expect(await screen.findByText("当前密码不正确")).toBeTruthy();
  });

  it("cannot be dismissed while a temporary password must be replaced, but allows signing out", async () => {
    const onLogout = vi.fn();
    render(<ChangePasswordModal open forced client={{} as ApiClient} username="lin" onClose={() => {}} onChanged={() => {}} onLogout={onLogout} />);
    expect(screen.getByText("请先设置自己的密码")).toBeTruthy();
    expect(document.querySelector(".ant-modal-close")).toBeNull();
    fireEvent.click(screen.getByRole("button", named("退出登录")));
    expect(onLogout).toHaveBeenCalled();
  });
});
