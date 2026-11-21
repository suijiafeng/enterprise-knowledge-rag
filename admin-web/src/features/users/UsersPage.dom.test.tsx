// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient, DirectoryUser, Grant } from "../../api/client";
import { UsersPage, userLabel } from "./UsersPage";

afterEach(cleanup);

const A1 = "00000000-0000-4000-8000-0000000000a1";
const A3 = "00000000-0000-4000-8000-0000000000a3";
const directory: DirectoryUser[] = [
  { id: A1, displayName: "林知远（示例）", email: "lin@example.com", department: "信息技术部" },
  { id: A3, displayName: "陈一鸣（示例）", email: "chen@example.com", department: "财务部" },
];

function clientWith(grants: Grant[]) {
  return {
    me: vi.fn(async () => ({ userId: A1, enterpriseId: "e", roles: ["IT_ADMIN"], knowledgeBaseIds: [] })),
    rolePolicy: vi.fn(async () => ({ roles: [], actions: [], rules: {} })),
    adminKnowledgeBases: vi.fn(async () => [{ id: "kb", name: "员工制度", memberCount: grants.length, createdAt: "2030-01-01T00:00:00Z" }]),
    grants: vi.fn(async () => grants),
    users: vi.fn(async (q: string) => directory.filter((u) => !q || userLabel(u).includes(q) || u.id === q)),
    grant: vi.fn(async () => {}),
    revoke: vi.fn(async () => {}),
    accounts: vi.fn(async () => []),
  } as unknown as ApiClient & Record<string, ReturnType<typeof vi.fn>>;
}

/** 管理员默认落在「账号」页，授权在第二个页签。 */
async function openGrants() {
  fireEvent.click(await screen.findByRole("tab", { name: "知识库授权" }));
}

describe("grants by name", () => {
  it("shows member names instead of raw ids, and marks members missing from the directory", async () => {
    const client = clientWith([
      { userId: A1, grantedAt: "2030-01-01T00:00:00Z", displayName: "林知远（示例）", email: "lin@example.com", department: "信息技术部" },
      { userId: "00000000-0000-4000-8000-0000000000ff", grantedAt: "2030-01-02T00:00:00Z", displayName: null },
    ]);
    render(<UsersPage client={client} />);
    await openGrants();
    expect(await screen.findByText("林知远（示例）")).toBeTruthy();
    expect(screen.getByText("信息技术部 · lin@example.com")).toBeTruthy();
    expect(screen.getByText("未同步到目录")).toBeTruthy();
    expect(screen.queryByText(A1)).toBeNull();
  });

  it("searches the directory by name and grants the picked user by id", async () => {
    const client = clientWith([{ userId: A1, grantedAt: "2030-01-01T00:00:00Z", displayName: "林知远（示例）" }]);
    render(<UsersPage client={client} />);
    await openGrants();
    const picker = await screen.findByLabelText("选择用户");
    fireEvent.mouseDown(picker);
    fireEvent.change(picker, { target: { value: "财务" } });
    await waitFor(() => expect(client.users).toHaveBeenLastCalledWith("财务"));
    fireEvent.click(await screen.findByText("陈一鸣（示例）", { selector: ".user-option span" }));
    fireEvent.click(screen.getByRole("button", { name: /授\s*权/ }));
    await waitFor(() => expect(client.grant).toHaveBeenCalledWith("kb", A3));
    expect(await screen.findByText("已授权，下一次请求立即生效")).toBeTruthy();
  });

  it("still allows granting a pasted user id that is not in the directory", async () => {
    const client = clientWith([]);
    const unknown = "00000000-0000-4000-8000-0000000000ee";
    render(<UsersPage client={client} />);
    await openGrants();
    const picker = await screen.findByLabelText("选择用户");
    fireEvent.mouseDown(picker);
    fireEvent.change(picker, { target: { value: unknown } });
    fireEvent.click(await screen.findByText("按 ID 授权"));
    fireEvent.click(screen.getByRole("button", { name: /授\s*权/ }));
    await waitFor(() => expect(client.grant).toHaveBeenCalledWith("kb", unknown));
  });
});
