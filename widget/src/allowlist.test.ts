import { expect, test } from "vitest";

import { filterProperties } from "./allowlist";

test("keeps only allowlisted scalar properties", () => {
  const filtered = filterProperties(
    {
      plan: "pro",
      seats: 4,
      trial: false,
      password: "hunter2",
      nested: { plan: "pro" } as unknown as string,
    },
    new Set(["plan", "seats", "trial", "nested"]),
  );
  expect(filtered).toEqual({ plan: "pro", seats: 4, trial: false });
});

test("caps at the contract's 20 properties", () => {
  const properties = Object.fromEntries(
    Array.from({ length: 25 }, (_, i) => [`key_${i}`, i]),
  );
  const filtered = filterProperties(properties, new Set(Object.keys(properties)));
  expect(Object.keys(filtered)).toHaveLength(20);
});
