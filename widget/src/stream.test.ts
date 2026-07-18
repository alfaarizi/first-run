// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { connectStream } from "./stream";
import type { Config } from "./types";

vi.mock("./request", () => ({
  sign: () => Promise.resolve("f".repeat(64)),
}));

class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readyState = FakeEventSource.CONNECTING;
  readonly url: string;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, handle: (event: MessageEvent<string>) => void): void {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), handle]);
  }

  close(): void {
    this.readyState = FakeEventSource.CLOSED;
  }

  emit(name: string, data = "{}", lastEventId = ""): void {
    for (const handle of this.listeners.get(name) ?? []) {
      handle({ data, lastEventId } as MessageEvent<string>);
    }
  }
}

const config: Config = {
  key: "key_1",
  secret: "secret",
  host: "https://api.example",
  allowlist: new Set(),
};

function handlers() {
  return { nudge: vi.fn(), token: vi.fn(), done: vi.fn(), action: vi.fn() };
}

beforeEach(() => {
  vi.useFakeTimers();
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  sessionStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test("a server-closed stream reopens", async () => {
  connectStream(config, "user-1", handlers());
  await vi.advanceTimersByTimeAsync(0);

  const first = FakeEventSource.instances[0]!;
  first.readyState = FakeEventSource.CLOSED;
  first.emit("error");

  await vi.advanceTimersByTimeAsync(60_000);
  expect(FakeEventSource.instances).toHaveLength(2);
});

test("a reopened stream resumes after the last seen frame", async () => {
  connectStream(config, "user-resume", handlers());
  await vi.advanceTimersByTimeAsync(0);

  const first = FakeEventSource.instances[0]!;
  first.emit("nudge", '{"id":"n1","text":"hi"}', "n1");
  first.readyState = FakeEventSource.CLOSED;
  first.emit("error");

  await vi.advanceTimersByTimeAsync(60_000);
  expect(FakeEventSource.instances[1]!.url).toContain("last_event_id=n1");
});

test("a reconnect that never saw a frame asks for the whole buffer", async () => {
  connectStream(config, "user-blank", handlers());
  await vi.advanceTimersByTimeAsync(0);

  const first = FakeEventSource.instances[0]!;
  first.emit("open");
  first.readyState = FakeEventSource.CLOSED;
  first.emit("error");

  await vi.advanceTimersByTimeAsync(60_000);
  expect(FakeEventSource.instances[1]!.url).toContain("last_event_id=earliest");
});

test("a fresh stream that never opened sends no cursor", async () => {
  connectStream(config, "user-fresh", handlers());
  await vi.advanceTimersByTimeAsync(0);

  expect(FakeEventSource.instances[0]!.url).not.toContain("last_event_id");
});

test("a replayed frame that races its live copy shows once", async () => {
  const handle = handlers();
  connectStream(config, "user-race", handle);
  await vi.advanceTimersByTimeAsync(0);

  const source = FakeEventSource.instances[0]!;
  source.emit("nudge", '{"id":"n1","text":"hi"}', "n1");
  source.emit("nudge", '{"id":"n1","text":"hi"}', "n1");

  expect(handle.nudge).toHaveBeenCalledTimes(1);
});

test("the cursor persists per end user across connections", async () => {
  const close = connectStream(config, "user-pages", handlers());
  await vi.advanceTimersByTimeAsync(0);
  FakeEventSource.instances[0]!.emit("nudge", '{"id":"n2","text":"hi"}', "n2");
  close();

  connectStream(config, "user-pages", handlers());
  await vi.advanceTimersByTimeAsync(0);
  expect(FakeEventSource.instances[1]!.url).toContain("last_event_id=n2");

  // another user's stream never resumes from this one's cursor
  connectStream(config, "user-other", handlers());
  await vi.advanceTimersByTimeAsync(0);
  expect(FakeEventSource.instances[2]!.url).not.toContain("last_event_id");
});

test("a page that opened without a frame still claims the buffer on the next page", async () => {
  const close = connectStream(config, "user-gap", handlers());
  await vi.advanceTimersByTimeAsync(0);
  FakeEventSource.instances[0]!.emit("open");
  close();

  // the next page must replay a first nudge buffered during the navigation gap
  connectStream(config, "user-gap", handlers());
  await vi.advanceTimersByTimeAsync(0);
  expect(FakeEventSource.instances[1]!.url).toContain("last_event_id=earliest");
});

test("one app's cursor never resumes or displaces another app's", async () => {
  const close = connectStream(config, "user-apps", handlers());
  await vi.advanceTimersByTimeAsync(0);
  FakeEventSource.instances[0]!.emit("nudge", '{"id":"n7","text":"hi"}', "n7");
  close();

  // a second app on the same origin, same customer-supplied hash, starts fresh
  const closeOther = connectStream({ ...config, key: "key_2" }, "user-apps", handlers());
  await vi.advanceTimersByTimeAsync(0);
  expect(FakeEventSource.instances[1]!.url).not.toContain("last_event_id");
  FakeEventSource.instances[1]!.emit("open");
  closeOther();

  // and its whole-buffer claim never displaces the first app's cursor
  connectStream(config, "user-apps", handlers());
  await vi.advanceTimersByTimeAsync(0);
  expect(FakeEventSource.instances[2]!.url).toContain("last_event_id=n7");
});

test("a retired stream closes for good instead of reconnecting", async () => {
  connectStream(config, "user-1", handlers());
  await vi.advanceTimersByTimeAsync(0);

  const source = FakeEventSource.instances[0]!;
  source.emit("retired");
  expect(source.readyState).toBe(FakeEventSource.CLOSED);

  // even a late error must not schedule a reopen once the stream is retired
  source.emit("error");
  await vi.advanceTimersByTimeAsync(60_000);
  expect(FakeEventSource.instances).toHaveLength(1);
});
