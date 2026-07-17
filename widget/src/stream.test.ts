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
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(_url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, handle: (event: MessageEvent<string>) => void): void {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), handle]);
  }

  close(): void {
    this.readyState = FakeEventSource.CLOSED;
  }

  emit(name: string, data = "{}"): void {
    for (const handle of this.listeners.get(name) ?? []) {
      handle({ data, lastEventId: "" } as MessageEvent<string>);
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
