// @vitest-environment happy-dom
import { renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { usePollingLoad } from "./usePollingLoad";

afterEach(() => vi.useRealTimers());

describe("usePollingLoad", () => {
  it("runs the next round only after the current one finishes, and folds extra ticks into one", async () => {
    vi.useFakeTimers();
    let release: () => void = () => undefined;
    const load = vi.fn(() => new Promise<void>((resolve) => { release = resolve; }));
    renderHook(() => usePollingLoad(true, 1000, load));

    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);

    release();
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });

  it("stops scheduling after the component unmounts", async () => {
    vi.useFakeTimers();
    let release: () => void = () => undefined;
    const load = vi.fn(() => new Promise<void>((resolve) => { release = resolve; }));
    const { unmount } = renderHook(() => usePollingLoad(true, 1000, load));

    await vi.advanceTimersByTimeAsync(1000);
    expect(load).toHaveBeenCalledTimes(1);
    unmount();
    release();
    await vi.advanceTimersByTimeAsync(5000);
    expect(load).toHaveBeenCalledTimes(1);
  });
});
