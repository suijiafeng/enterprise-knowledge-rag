// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type ApiClient, type DocumentInfo } from "../../api/client";
import { UploadDocumentModal, nextVersionLabel, titleFromFile } from "./UploadDocumentModal";

afterEach(cleanup);

const existing: DocumentInfo = { id: "d1", title: "年假办法", createdAt: "2030-01-01T00:00:00Z", versions: [{
  id: "v1", versionLabel: "v1", effectiveFrom: "2030-01-01T00:00:00Z", effectiveUntil: null, approved: true, published: true,
  withdrawn: false, superseded: false, indexStatus: "ready", indexError: null,
}] };

function clientWith(overrides: Partial<Record<keyof ApiClient, unknown>> = {}) {
  return {
    createDocument: vi.fn(async () => ({ id: "new-doc" })),
    createVersion: vi.fn(async () => ({ id: "new-version" })),
    upload: vi.fn(async () => ({ jobId: "j" })),
    transition: vi.fn(async () => {}),
    ...overrides,
  } as unknown as ApiClient & Record<string, ReturnType<typeof vi.fn>>;
}

function pick(name: string) {
  const input = document.querySelector<HTMLInputElement>(".ant-upload input[type=file]");
  if (!input) throw new Error("no upload input");
  const file = new File(["# 差旅办法\n\n出差住宿按城市分级。"], name, { type: "text/markdown" });
  fireEvent.change(input, { target: { files: [file] } });
  return file;
}

describe("upload document modal", () => {
  it("derives the title and next version label", () => {
    expect(titleFromFile("差旅办法.md")).toBe("差旅办法");
    expect(titleFromFile("notes.TXT")).toBe("notes");
    expect(titleFromFile("员工手册.pdf")).toBe("员工手册");
    expect(titleFromFile("报销制度.DOCX")).toBe("报销制度");
    expect(nextVersionLabel(undefined)).toBe("v1");
    expect(nextVersionLabel(existing)).toBe("v2");
  });

  it("creates the document and version, uploads, then publishes in one step", async () => {
    const client = clientWith();
    const onUploaded = vi.fn();
    render(<UploadDocumentModal open client={client} kbId="kb" docs={[]} onClose={() => {}} onUploaded={onUploaded} />);
    const ok = () => screen.getByRole("button", { name: /上\s*传\s*并\s*发\s*布/ });
    expect(ok().hasAttribute("disabled")).toBe(true);
    const file = pick("差旅办法.md");
    await waitFor(() => expect(ok().hasAttribute("disabled")).toBe(false));
    fireEvent.click(ok());
    await waitFor(() => expect(onUploaded).toHaveBeenCalledWith("差旅办法", true));
    expect(client.createDocument).toHaveBeenCalledWith("kb", "差旅办法");
    expect(client.createVersion).toHaveBeenCalledWith("kb", "new-doc", expect.objectContaining({ versionLabel: "v1" }));
    expect(client.upload).toHaveBeenCalledWith("kb", "new-doc", "new-version", file);
    expect(client.transition).toHaveBeenCalledTimes(1);
    expect(client.transition).toHaveBeenCalledWith("kb", "new-doc", "new-version", "publish");
  });

  it("only uploads when the deployment requires a separate approval", async () => {
    const client = clientWith();
    const onUploaded = vi.fn();
    render(<UploadDocumentModal open requireApproval client={client} kbId="kb" docs={[]} onClose={() => {}} onUploaded={onUploaded} />);
    expect(screen.getByText(/本部署要求审批/)).toBeTruthy();
    expect(screen.queryByRole("checkbox")).toBeNull();
    pick("员工手册.pdf");
    const ok = () => screen.getByRole("button", { name: /^上\s*传$/ });
    await waitFor(() => expect(ok().hasAttribute("disabled")).toBe(false));
    fireEvent.click(ok());
    await waitFor(() => expect(onUploaded).toHaveBeenCalledWith("员工手册", false));
    expect(client.transition).not.toHaveBeenCalled();
  });

  it("accepts pdf and docx files", () => {
    render(<UploadDocumentModal open client={clientWith()} kbId="kb" docs={[]} onClose={() => {}} onUploaded={() => {}} />);
    expect(document.querySelector(".ant-upload input[type=file]")?.getAttribute("accept")).toBe(".md,.markdown,.txt,.pdf,.docx");
  });

  it("adds a version to an existing document and says which step failed", async () => {
    const client = clientWith({ upload: vi.fn(async () => { throw new ApiError(409, "CONTENT_EXISTS", "内容已存在"); }) });
    const onUploaded = vi.fn();
    render(<UploadDocumentModal open client={client} kbId="kb" docs={[existing]} presetDocId="d1" onClose={() => {}} onUploaded={onUploaded} />);
    expect((screen.getByPlaceholderText("v1") as HTMLInputElement).value).toBe("v2");
    pick("年假办法-v2.md");
    await waitFor(() => expect(screen.getByRole("button", { name: /上\s*传/ }).hasAttribute("disabled")).toBe(false));
    fireEvent.click(screen.getByRole("button", { name: /上\s*传/ }));
    expect(await screen.findByText(/上传内容失败：内容已存在/)).toBeTruthy();
    expect(client.createDocument).not.toHaveBeenCalled();
    expect(client.transition).not.toHaveBeenCalled();
    expect(onUploaded).not.toHaveBeenCalled();
  });
});
