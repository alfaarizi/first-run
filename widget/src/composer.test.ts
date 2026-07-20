// @vitest-environment jsdom
import { expect, test } from "vitest";

import { createComposer } from "./composer";

test("the input caps a draft at the messages contract's 2000 characters", () => {
  const composer = createComposer(() => {});

  const input = composer.root.querySelector("textarea");
  expect(input?.maxLength).toBe(2000);
});
