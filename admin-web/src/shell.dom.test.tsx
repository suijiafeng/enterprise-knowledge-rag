// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { DevBanner } from "./App";
import { NAV, visibleNav } from "./nav";
import { LAZY_ROUTES } from "./routes";

afterEach(cleanup);

describe("console shell", () => {
  it("shows the dev notice as one small dismissible line", () => {
    render(<DevBanner connected />);
    const note = screen.getByRole("note");
    expect(note.textContent).toContain("尚未接入生产服务");
    expect(note.textContent).toContain("请结合原文判断回答是否准确");
    expect(note.textContent).not.toContain("metadata-only");
    expect(document.querySelector(".ant-alert")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "关闭提示" }));
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("lazy-loads every navigation entry as its own route", () => {
    expect(Object.keys(LAZY_ROUTES).sort()).toEqual(NAV.map((n) => n.key).sort());
  });

  it("trims the menu by role and shows everything in sample mode", () => {
    const keys = (roles?: string[]) => visibleNav(roles).map((n) => n.key);
    expect(keys(undefined)).toEqual(NAV.map((n) => n.key));
    expect(keys(["EMPLOYEE"])).toEqual(["overview", "qa", "records"]);
    expect(keys(["KB_OPERATOR"])).toEqual(["overview", "documents", "qa", "records", "debug", "evaluation", "integration", "settings"]);
    expect(keys(["IT_ADMIN"])).toEqual(["overview", "qa", "records", "integration", "users", "settings"]);
    expect(keys(["IT_ADMIN", "KB_OPERATOR"])).toEqual(NAV.map((n) => n.key));
    expect(keys([])).toEqual([]);
  });
});
