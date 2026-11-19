/** 所有选择器均以 .zhiku- 为前缀，避免样式泄漏到宿主页面。 */
export const WIDGET_CSS = `
.zhiku-root{position:fixed;right:20px;bottom:20px;z-index:2147483000;font:14px/1.6 ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Noto Sans SC","Microsoft YaHei",sans-serif;color:#18181b;-webkit-font-smoothing:antialiased;font-variant-numeric:tabular-nums}
.zhiku-launcher{width:44px;height:44px;border-radius:8px;border:1px solid var(--zhiku-primary);background:var(--zhiku-primary);color:#fff;display:grid;place-items:center;cursor:pointer;box-shadow:0 1px 2px rgba(0,0,0,.06);transition:opacity 150ms ease-out,transform 150ms ease-out}
.zhiku-launcher:hover{opacity:.92}
.zhiku-launcher:active,.zhiku-send:active{transform:scale(.98)}
.zhiku-panel{width:380px;height:620px;max-height:calc(100vh - 40px);background:#fff;border:1px solid #e4e4e7;border-radius:12px;box-shadow:0 1px 2px rgba(0,0,0,.04),0 8px 24px rgba(0,0,0,.06);display:flex;flex-direction:column;overflow:hidden}
.zhiku-header{display:flex;align-items:center;gap:10px;padding:12px 14px;background:#fff;color:#18181b;border-bottom:1px solid #e4e4e7}
.zhiku-header h2{margin:0;font-size:14px;font-weight:600;letter-spacing:normal}
.zhiku-header p{margin:0;font-size:13px;color:#71717a}
.zhiku-logo{width:28px;height:28px;border-radius:6px;border:1px solid #e4e4e7;background:#fafafa;color:#18181b;display:grid;place-items:center;font-weight:600;font-size:13px}
.zhiku-close{margin-left:auto;border:0;background:transparent;color:#52525b;font-size:18px;line-height:1;cursor:pointer;border-radius:6px;width:28px;height:28px}
.zhiku-close:hover{background:#f4f4f5}
.zhiku-body{flex:1;overflow-y:auto;padding:14px;display:flex;flex-direction:column;gap:10px;background:#fafafa}
.zhiku-bot{align-self:flex-start;max-width:88%;background:#fff;border:1px solid #e4e4e7;border-radius:8px;padding:10px 12px;white-space:pre-wrap;overflow-wrap:anywhere}
.zhiku-user{align-self:flex-end;max-width:80%;background:#f4f4f5;border-radius:8px;padding:8px 12px;white-space:pre-wrap;overflow-wrap:anywhere}
.zhiku-quick{display:flex;flex-wrap:wrap;gap:8px}
.zhiku-chip{border:1px solid #e4e4e7;border-radius:6px;background:#fff;color:#18181b;padding:4px 10px;font:inherit;font-size:13px;cursor:pointer;transition:background-color 150ms ease-out,border-color 150ms ease-out}
.zhiku-chip:hover{background:#f4f4f5}
.zhiku-cite{border:0;background:none;color:#18181b;padding:0 1px;font:inherit;cursor:pointer;text-decoration:underline;text-underline-offset:2px}
.zhiku-source{margin-top:8px;background:#fafafa;border-radius:6px;padding:8px 10px;font-size:13px;color:#3f3f46}
.zhiku-source strong{display:block;color:#18181b;font-weight:600}
.zhiku-note{margin:6px 0 0;font-size:13px;color:#71717a}
.zhiku-error{color:#9f1239}
.zhiku-feedback{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-top:8px;font-size:13px;color:#52525b}
.zhiku-feedback button{border:1px solid #e4e4e7;background:#fff;border-radius:6px;padding:2px 10px;font:inherit;font-size:13px;cursor:pointer;transition:background-color 150ms ease-out,border-color 150ms ease-out,color 150ms ease-out}
.zhiku-feedback button[aria-pressed="true"]{border-color:#18181b;color:#18181b}
.zhiku-form{display:flex;gap:8px;padding:10px 12px;border-top:1px solid #e4e4e7;background:#fff}
.zhiku-form textarea{flex:1;resize:none;border:1px solid #e4e4e7;border-radius:6px;padding:6px 8px;font:inherit;min-width:0}
.zhiku-send,.zhiku-stop{border:0;border-radius:6px;background:var(--zhiku-primary);color:#fff;padding:0 14px;font:inherit;cursor:pointer;transition:opacity 150ms ease-out,transform 150ms ease-out}
.zhiku-send:disabled{opacity:.45;cursor:not-allowed}
.zhiku-stop{background:#18181b;padding:4px 12px;align-self:flex-start}
@media (max-width:480px){.zhiku-root{right:0;bottom:0}.zhiku-panel{width:100vw;height:100vh;max-height:100vh;border-radius:0;box-shadow:none}.zhiku-root .zhiku-launcher{margin:0 16px 16px 0}}
@media (prefers-reduced-motion:reduce){.zhiku-launcher:active,.zhiku-send:active{transform:none}}
`;
