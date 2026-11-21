import { expect, test } from "@playwright/test";
import { label, login, loginAs, logout, openMenu, stamp } from "./helpers";

test("admin creates an account, the user replaces the one-time password, admin disables it", async ({ page }) => {
  const username = `e2e.${stamp()}`;
  const displayName = `测试用户（虚构）`;
  const newPassword = `e2e-New-Secret-${stamp()}`;

  await loginAs(page, "admin", "林知远（示例）");
  await openMenu(page, "用户与权限");
  await page.getByRole("button", { name: "新建账号" }).click();
  const create = page.getByRole("dialog", { name: "新建账号" });
  await create.getByLabel("用户名").fill(username);
  await create.getByLabel("显示名").fill(displayName);
  await create.getByRole("button", { name: label("创建") }).click();
  const shown = page.getByRole("dialog", { name: "账号已创建" });
  const temporary = (await shown.locator(".temp-password code").innerText()).trim();
  expect(temporary).toHaveLength(16);
  await shown.getByRole("button", { name: "我已转交" }).click();
  await expect(page.locator("tr", { hasText: username }).getByText("待改密")).toBeVisible();
  await logout(page);

  await login(page, username, temporary);
  const change = page.getByRole("dialog", { name: "请先设置自己的密码" });
  await expect(change).toBeVisible();
  await expect(change.locator(".ant-modal-close")).toHaveCount(0);
  await change.getByLabel("临时密码").fill(temporary);
  await change.getByLabel("新密码", { exact: true }).fill(newPassword);
  await change.getByLabel("确认新密码").fill(newPassword);
  await change.getByRole("button", { name: "修改密码" }).click();
  await expect(page.getByText("密码已修改，其他设备已下线")).toBeVisible();
  await expect(change).toBeHidden();
  await logout(page);

  await loginAs(page, "admin", "林知远（示例）");
  await openMenu(page, "用户与权限");
  const row = page.locator("tr", { hasText: username });
  await row.getByRole("button", { name: label("停用") }).click();
  await page.locator(".ant-popover").getByRole("button", { name: label("停用") }).click();
  await expect(row.getByText("已停用")).toBeVisible();
  await logout(page);

  await login(page, username, newPassword);
  await expect(page.getByText("用户名或密码不正确")).toBeVisible();
});
