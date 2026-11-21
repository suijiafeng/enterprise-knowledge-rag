import { expect, test } from "@playwright/test";
import { label, loginAs, logout, openMenu, stamp } from "./helpers";

test("upload and publish in one step, then ask and get a cited answer", async ({ page }) => {
  const id = stamp();
  const title = `门禁卡办理办法（虚构-${id}）`;
  // 虚构制度：带一个唯一的事实，问答时只能从这份文档里找到。
  const body = `# ${title}\n\n本文是端到端测试用的虚构示例，不是企业制度。\n\n## 补办\n\n门禁卡遗失后，员工须在两个工作日内到前台补办，补办费用为 ${id.slice(-2)} 元。\n`;

  await loginAs(page, "operator", "周若溪（示例）");
  await openMenu(page, "文档管理");
  await page.getByRole("button", { name: "上传文档" }).click();
  const dialog = page.getByRole("dialog", { name: "上传文档" });
  await dialog.locator("input[type=file]").setInputFiles({ name: `${title}.md`, mimeType: "text/markdown", buffer: Buffer.from(body) });
  await expect(dialog.getByLabel("文档标题")).toHaveValue(title);
  await dialog.getByRole("button", { name: label("上传并发布") }).click();
  await expect(page.getByText(`《${title}》已上传并发布，正在建立索引`)).toBeVisible();

  const row = page.locator("tr", { hasText: title }).first();
  await row.getByRole("button", { name: /展开行|Expand row/ }).click();
  const version = page.locator(".version-panel tr", { hasText: "v1" }).first();
  await expect(version.getByText("已发布")).toBeVisible();
  await expect(version.getByText("已索引")).toBeVisible({ timeout: 45_000 });
  await expect(version.getByRole("button", { name: label("批准") })).toHaveCount(0);

  await openMenu(page, "问答工作台");
  await page.getByLabel("问题输入").fill("门禁卡遗失后多久内要补办？");
  await page.getByRole("button", { name: label("发送") }).click();
  const answer = page.locator(".cite-mark").first();
  await expect(answer).toBeVisible({ timeout: 45_000 });
  await expect(page.locator(".source-card .source-title", { hasText: title }).first()).toBeVisible();
  await logout(page);
});
