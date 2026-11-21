import { App, message as staticMessage } from "antd";

/**
 * 一次性提示（成功、已恢复、可忽略的失败）统一用 antd message 浮层，不再占用页面顶部。
 * 阻断操作或需要用户处理的错误仍放在页面里。组件脱离 <App> 渲染（单测）时退回静态 message。
 */
export function useNotice() {
  const { message } = App.useApp();
  return typeof message?.success === "function" ? message : staticMessage;
}

export const NOTICE_DURATION = { short: 2, long: 4 } as const;
