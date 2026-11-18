import { useEffect, useRef } from "react";

/**
 * 文档状态轮询。计时器照常响起，但同一时刻只有一轮请求：进行中又被触发时只合并成
 * “结束后再跑一次”。这一轮结束后才开始下一次，组件卸载后不再调度。
 */
export function usePollingLoad(enabled: boolean, intervalMs: number, load: () => Promise<void>): void {
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    if (!enabled) return undefined;
    let stopped = false;
    let inflight = false;
    let again = false;
    const run = () => {
      if (stopped) return;
      if (inflight) {
        again = true;
        return;
      }
      inflight = true;
      again = false;
      void loadRef.current().finally(() => {
        inflight = false;
        if (stopped) return;
        if (again) run();
      });
    };
    const timer = setInterval(run, intervalMs);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [enabled, intervalMs]);
}
