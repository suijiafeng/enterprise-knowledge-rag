import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { IntegrationPage, embedSnippet, tokenSnippet } from "./IntegrationPage";

describe("api and integration", () => {
  it("builds an embed snippet for the selected knowledge base without long-lived keys", () => {
    const code = embedSnippet("kb-123", "#1D5BBF");
    expect(code).toContain('knowledgeBaseId="kb-123"');
    expect(code).toContain("getAccessToken");
    expect(code).toContain('themeColor="#1D5BBF"');
    for (const bad of ["sk_", "pk_", "data-key", "data-widget-id", "apiKey"]) expect(code).not.toContain(bad);
  });

  it("documents the server-side token exchange", () => {
    const code = tokenSnippet("kb-123");
    expect(code).toContain("POST /api/widget-tokens");
    expect(code).toContain('"kbId":"kb-123"');
  });

  it("does not render phase-two controls", () => {
    const html = renderToStaticMarkup(<IntegrationPage kbId="kb-123" kbName="制度库" />);
    expect(html).not.toContain("新建 Key");
    expect(html).not.toContain("回调 URL");
    expect(html).toContain("第二阶段");
    expect(html).toContain("不提供配置入口");
    expect(html).not.toContain("128,420");
  });

  it("asks for a knowledge base before generating code", () => {
    expect(renderToStaticMarkup(<IntegrationPage kbId={undefined} kbName={undefined} />)).toContain("选择知识库");
  });
});
