// @vitest-environment jsdom
import { afterEach, expect, test } from "vitest";

import { clearChat, loadChat, storeChat } from "./chat-store";
import type { ChatSnapshot } from "./types";

afterEach(() => sessionStorage.clear());

const snapshot: ChatSnapshot = {
  open: true,
  messages: [
    { who: "user", text: "how do I connect?", at: 1_000 },
    { who: "agent", text: "Open Settings.", at: 2_000, citations: [{ title: "Docs", url: "https://x" }] },
  ],
  pendingId: "m1",
};

test("round-trips a snapshot for one app and end user", () => {
  storeChat("key_1", "user-1", snapshot);
  expect(loadChat("key_1", "user-1")).toEqual(snapshot);
});

test("round-trips the composer draft and scroll offset", () => {
  const withView: ChatSnapshot = { ...snapshot, composerDraft: "half a question", scrollTop: 240 };
  storeChat("key_1", "user-1", withView);
  expect(loadChat("key_1", "user-1")).toEqual(withView);
});

test("clearChat drops a stored chat", () => {
  storeChat("key_1", "user-1", snapshot);
  clearChat("key_1", "user-1");
  expect(loadChat("key_1", "user-1")).toBeUndefined();
});

test("keeps one app and user's chat out of another's", () => {
  storeChat("key_1", "user-1", snapshot);

  expect(loadChat("key_1", "user-2")).toBeUndefined();
  expect(loadChat("key_2", "user-1")).toBeUndefined();
});

test("reads absent when nothing was stored", () => {
  expect(loadChat("key_1", "user-1")).toBeUndefined();
});

test("rejects a payload from another schema version rather than restoring it", () => {
  sessionStorage.setItem("fr_chat:key_1:user-1", JSON.stringify({ v: 99, open: true, messages: [] }));

  expect(loadChat("key_1", "user-1")).toBeUndefined();
});

test("rejects a corrupt entry rather than throwing", () => {
  sessionStorage.setItem("fr_chat:key_1:user-1", "{not json");

  expect(loadChat("key_1", "user-1")).toBeUndefined();
});

test("caps the stored transcript to bound one user's storage", () => {
  const many: ChatSnapshot = {
    open: false,
    messages: Array.from({ length: 60 }, (_, i) => ({
      who: i % 2 === 0 ? "user" : "agent",
      text: `m${i}`,
      at: i,
    })),
  };
  storeChat("key_1", "user-1", many);

  const restored = loadChat("key_1", "user-1");
  expect(restored?.messages).toHaveLength(40);

  // the newest messages survive, the oldest scroll out of the store
  expect(restored?.messages.at(-1)?.text).toBe("m59");
  expect(restored?.messages.at(0)?.text).toBe("m20");
});
