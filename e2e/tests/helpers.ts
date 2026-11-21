import { expect, type Page } from "@playwright/test";

export const DEMO_PASSWORD = process.env.E2E_DEMO_PASSWORD ?? "zhiku-demo-2026";

/** antd 会在两个汉字的按钮文字中间插空格；按钮名按「可有空白」匹配。 */
export const label = (text: string) => new RegExp(`^${text.split("").join("\\s*")}$`);

export async function login(page: Page, username: string, password = DEMO_PASSWORD) {
  await page.goto("/");
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: label("登录") }).click();
}

export async function loginAs(page: Page, username: string, displayName: string) {
  await login(page, username);
  await expect(page.getByText(`欢迎回来，${displayName}`)).toBeVisible();
  await expect(page.locator(".shell-menu")).toBeVisible();
}

export async function logout(page: Page) {
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "登录管理控制台" })).toBeVisible();
}

export async function openMenu(page: Page, name: string) {
  await page.locator(".shell-menu").getByText(name, { exact: true }).click();
}

export const stamp = () => new Date().toISOString().replace(/\D/g, "").slice(4, 14);
