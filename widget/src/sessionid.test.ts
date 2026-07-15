import { expect, test } from "vitest";

import { rotateSession, sessionId } from "./sessionid";

const T0 = 1_700_000_000_000;
const MINUTE = 60 * 1000;

test("keeps the session across activity inside the idle window", () => {
  const id = sessionId(T0);
  expect(sessionId(T0 + 29 * MINUTE)).toBe(id);
});

test("rotates the session after 30 idle minutes", () => {
  const id = sessionId(T0);
  expect(sessionId(T0 + 31 * MINUTE)).not.toBe(id);
});

test("rotates the session on demand", () => {
  const id = sessionId(T0);
  rotateSession(T0);
  expect(sessionId(T0)).not.toBe(id);
});
