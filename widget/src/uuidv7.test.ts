import { expect, test } from "vitest";

import { uuidv7 } from "./uuidv7";

test("shapes a version 7 uuid with the variant bits set", () => {
  expect(uuidv7()).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
});

test("leads with the generation time in milliseconds", () => {
  const id = uuidv7();
  const ms = parseInt(id.slice(0, 8) + id.slice(9, 13), 16);
  expect(Math.abs(ms - Date.now())).toBeLessThan(1000);
});
