// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { post } from "./request";
import { RequestQueue } from "./request-queue";
import type { CapturedEvent, Config } from "./types";

vi.mock("./request", () => ({ post: vi.fn() }));

const postMock = vi.mocked(post);

const config: Config = {
  key: "pk",
  secret: "sk",
  host: "https://gw.example",
  allowlist: new Set(),
};

const event = (i: number, properties?: CapturedEvent["properties"]): CapturedEvent => ({
  id: `id_${i}`,
  event: "project_created",
  end_user_hash: "hash",
  session_id: "session",
  timestamp: "2026-07-16T00:00:00.000Z",
  ...(properties && { properties }),
});

const sentEvents = (call: number): CapturedEvent[] =>
  (JSON.parse(postMock.mock.calls[call]![2]) as { events: CapturedEvent[] }).events;

beforeEach(() => {
  vi.useFakeTimers();
  postMock.mockResolvedValue({ ok: true, retryable: false });
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

test("flushes at 20 events without waiting", async () => {
  const queue = new RequestQueue(config);
  for (let i = 0; i < 20; i++) queue.enqueue(event(i));
  await vi.advanceTimersByTimeAsync(0);

  expect(postMock).toHaveBeenCalledTimes(1);
  expect(sentEvents(0)).toHaveLength(20);
});

test("flushes a partial batch after five seconds", async () => {
  const queue = new RequestQueue(config);
  queue.enqueue(event(0));

  await vi.advanceTimersByTimeAsync(4999);
  expect(postMock).not.toHaveBeenCalled();

  await vi.advanceTimersByTimeAsync(1);
  expect(postMock).toHaveBeenCalledTimes(1);
  expect(sentEvents(0)).toHaveLength(1);
});

test("retries a retryable failure with backoff and drops after three attempts", async () => {
  postMock.mockResolvedValue({ ok: false, retryable: true });
  const queue = new RequestQueue(config);
  queue.enqueue(event(0));

  await vi.advanceTimersByTimeAsync(5000);
  expect(postMock).toHaveBeenCalledTimes(1);

  await vi.advanceTimersByTimeAsync(2000);
  expect(postMock).toHaveBeenCalledTimes(2);

  await vi.advanceTimersByTimeAsync(4000);
  expect(postMock).toHaveBeenCalledTimes(3);

  await vi.advanceTimersByTimeAsync(60_000);
  expect(postMock).toHaveBeenCalledTimes(3);
});

test("drops a rejected batch without retrying", async () => {
  postMock.mockResolvedValue({ ok: false, retryable: false });
  const queue = new RequestQueue(config);
  queue.enqueue(event(0));

  await vi.advanceTimersByTimeAsync(60_000);
  expect(postMock).toHaveBeenCalledTimes(1);
});

test("drops an event over the per-event byte cap", async () => {
  const queue = new RequestQueue(config);
  queue.enqueue(event(0, { note: "x".repeat(33 * 1024) }));

  await vi.advanceTimersByTimeAsync(60_000);
  expect(postMock).not.toHaveBeenCalled();
});

test("flushes the queued tail once when the page hides", async () => {
  const queue = new RequestQueue(config);
  queue.enqueue(event(0));
  queue.enqueue(event(1));

  dispatchEvent(new Event("pagehide"));
  expect(postMock).toHaveBeenCalledTimes(1);
  expect(sentEvents(0)).toHaveLength(2);

  await vi.advanceTimersByTimeAsync(60_000);
  expect(postMock).toHaveBeenCalledTimes(1);
});

test("flushes when the tab becomes hidden", () => {
  const queue = new RequestQueue(config);
  queue.enqueue(event(0));

  vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
  document.dispatchEvent(new Event("visibilitychange"));

  expect(postMock).toHaveBeenCalledTimes(1);
  expect(sentEvents(0)).toHaveLength(1);
});

test("returns a network-refused hide flush to the queue for a surviving tab", async () => {
  postMock.mockResolvedValue({ ok: false, retryable: true });
  const queue = new RequestQueue(config);
  queue.enqueue(event(0));

  dispatchEvent(new Event("pagehide"));
  await vi.advanceTimersByTimeAsync(0);
  expect(postMock).toHaveBeenCalledTimes(1);

  postMock.mockResolvedValue({ ok: true, retryable: false });
  await vi.advanceTimersByTimeAsync(5000);
  expect(postMock).toHaveBeenCalledTimes(2);
  expect(sentEvents(1)).toHaveLength(1);
});
