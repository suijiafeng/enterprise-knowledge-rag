import { expect, test } from "@playwright/test";
import { label, login, loginAs, logout } from "./helpers";

test("wrong password shows a toast; the right one welcomes by display name", async ({ page }) => {
  await login(page, "admin", "not-the-password");
  await expect(page.getByText("用户名或密码不正确")).toBeVisible();
  await expect(page.getByRole("button", { name: label("登录") })).toBeVisible();

  await page.getByLabel("密码", { exact: true }).fill(process.env.E2E_DEMO_PASSWORD ?? "zhiku-demo-2026");
  await page.getByRole("button", { name: label("登录") }).click();
  await expect(page.getByText("欢迎回来，林知远（示例）")).toBeVisible();
  await logout(page);
});

test("the menu is trimmed by role", async ({ page }) => {
  await loginAs(page, "employee", "陈一鸣（示例）");
  const menu = page.locator(".shell-menu");
  await expect(menu.getByText("问答工作台", { exact: true })).toBeVisible();
  await expect(menu.getByText("文档管理", { exact: true })).toHaveCount(0);
  await expect(menu.getByText("用户与权限", { exact: true })).toHaveCount(0);
  await logout(page);
});
