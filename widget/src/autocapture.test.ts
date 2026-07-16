// @vitest-environment jsdom
import { expect, test, vi } from "vitest";

import { startAutocapture } from "./autocapture";

test("captures a page view on start and on history navigation", () => {
  const capture = vi.fn();
  startAutocapture(capture);

  expect(capture).toHaveBeenCalledWith("fr.page_view", { path: "/" });

  history.pushState({}, "", "/settings");
  expect(capture).toHaveBeenLastCalledWith("fr.page_view", { path: "/settings" });

  history.replaceState({}, "", "/settings/billing");
  expect(capture).toHaveBeenLastCalledWith("fr.page_view", { path: "/settings/billing" });
});

test("captures a control click by tag and id, never its text", () => {
  const capture = vi.fn();
  startAutocapture(capture);
  capture.mockClear();

  const button = document.createElement("button");
  button.id = "create-project";
  button.textContent = "Create Acme Rollout Plan";
  document.body.append(button);
  button.click();

  expect(capture).toHaveBeenCalledTimes(1);
  expect(capture).toHaveBeenCalledWith("fr.click", { tag: "button", id: "create-project" });
  button.remove();
});

test("ignores clicks outside interactive controls", () => {
  const capture = vi.fn();
  startAutocapture(capture);
  capture.mockClear();

  const div = document.createElement("div");
  document.body.append(div);
  div.click();

  expect(capture).not.toHaveBeenCalled();
  div.remove();
});

test("captures an error's location, never its message", () => {
  const capture = vi.fn();
  startAutocapture(capture);
  capture.mockClear();

  dispatchEvent(
    new ErrorEvent("error", {
      message: "boom for user@example.com",
      filename: "https://app.example/main.js",
      lineno: 42,
    }),
  );

  expect(capture).toHaveBeenCalledTimes(1);
  expect(capture).toHaveBeenCalledWith("fr.error", {
    source: "https://app.example/main.js",
    line: 42,
  });
});

test("captures an unhandled rejection without its reason", () => {
  const capture = vi.fn();
  startAutocapture(capture);
  capture.mockClear();

  dispatchEvent(new Event("unhandledrejection"));

  expect(capture).toHaveBeenCalledWith("fr.error", { source: "unhandledrejection", line: 0 });
});
