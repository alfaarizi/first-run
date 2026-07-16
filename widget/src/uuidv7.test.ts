import { expect, test } from "vitest";

import { isUuidv7, uuidv7 } from "./uuidv7";

test("shapes a version 7 uuid with the variant bits set", () => {
  expect(isUuidv7(uuidv7())).toBe(true);
});

test("leads with the generation time in milliseconds", () => {
  const id = uuidv7();
  const ms = parseInt(id.slice(0, 8) + id.slice(9, 13), 16);
  expect(Math.abs(ms - Date.now())).toBeLessThan(1000);
});

test("rejects a malformed or non-version-7 id", () => {
  expect(isUuidv7("not-a-uuid")).toBe(false);
  expect(isUuidv7("00000000-0000-4000-8000-000000000000")).toBe(false);
});
