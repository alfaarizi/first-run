// @vitest-environment jsdom
import { expect, test } from "vitest";

import { createComposer } from "./composer";

test("the input caps a draft at the messages contract's 2000 characters", () => {
  const composer = createComposer(() => {});

  const input = composer.root.querySelector("textarea");
  expect(input?.maxLength).toBe(2000);
});

test("blocking the composer closes the input and its send button together", () => {
  const composer = createComposer(() => {});
  const input = composer.root.querySelector("textarea")!;
  const send = composer.root.querySelector("button")!;

  composer.setEnabled(false);
  expect(input.disabled).toBe(true);
  expect(send.disabled).toBe(true);

  composer.setEnabled(true);
  expect(input.disabled).toBe(false);
  expect(send.disabled).toBe(false);
});
