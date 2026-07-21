// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { MESSAGES_PATH, TRY_AGAIN_TEXT } from "./constants";
import { post } from "./request";
import type { PostResult } from "./request";
import { connectStream } from "./stream";

vi.mock("./request", () => ({ post: vi.fn() }));
vi.mock("./stream", () => ({ connectStream: vi.fn() }));

const postMock = vi.mocked(post);
const connectStreamMock = vi.mocked(connectStream);

const ACCEPTED: PostResult = { ok: true, retryable: false, refused: false };
const REFUSED: PostResult = { ok: false, retryable: true, refused: true };
const UNAVAILABLE: PostResult = { ok: false, retryable: true, refused: false };

const attachShadow = HTMLElement.prototype.attachShadow;
let shadow: ShadowRoot;

// jsdom leaves scroll methods unimplemented
Element.prototype.scrollTo = () => {};

beforeEach(() => {
  // The module mocks outlive restoreAllMocks, so one test's sends would
  // otherwise still be on the record for the next.
  vi.clearAllMocks();
  document.body.replaceChildren();
  vi.spyOn(HTMLElement.prototype, "attachShadow").mockImplementation(function (
    this: HTMLElement,
    init: ShadowRootInit,
  ) {
    shadow = attachShadow.call(this, { ...init, mode: "open" });
    return shadow;
  });
  postMock.mockResolvedValue(ACCEPTED);
  connectStreamMock.mockReturnValue({ close: () => {}, isOpen: () => true });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.resetModules();
  delete window.fr;
});

const query = <T extends HTMLElement>(selector: string) => shadow.querySelector<T>(selector);

/** Boots the widget the way the snippet does, then identifies the end user. */
async function boot(): Promise<void> {
  const script = document.createElement("script");
  Object.assign(script.dataset, {
    key: "pk_test",
    host: "https://gw.example",
    secret: "sk_test",
  });
  document.body.append(script);

  // readConfig runs while the snippet is the executing script
  Object.defineProperty(document, "currentScript", { value: script, configurable: true });

  await import("./index");
  window.fr!.identify("9f86d081884c7d65");
}

/** Sends one question through the composer and lets the send's promise settle. */
async function ask(text = "how do I connect?"): Promise<void> {
  query<HTMLButtonElement>(".fr-launcher")?.click();
  const input = query<HTMLTextAreaElement>(".fr-input")!;

  input.value = text;
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  // A timeout runs after the microtask queue drains, so the send settles however
  // many promises deep its chain runs. Counting the ticks would pin the tests to
  // the shape of that chain rather than to what it decides.
  await new Promise((resolve) => setTimeout(resolve, 0));
}

const sentMessage = () => postMock.mock.calls.find(([, path]) => path === MESSAGES_PATH);

test("a question the server refused settles on the retry line at once", async () => {
  // Both of the endpoint's 429s are raised before the message is forwarded, so
  // no frame can follow one and waiting out the answer watchdog is dead time.
  postMock.mockResolvedValue(REFUSED);
  await boot();

  await ask();

  expect(sentMessage()).toBeDefined();
  expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT);
  expect(query(".fr-typing")).toBeNull();
});

test("a question of unknown fate keeps waiting for its answer", async () => {
  // The request may have reached the server, whose answer then rides the
  // stream. Settling it now would render the retry line over a live answer.
  postMock.mockResolvedValue(UNAVAILABLE);
  await boot();

  await ask();

  expect(query(".fr-typing")).not.toBeNull();
  expect(query(".fr-message-agent .fr-body")?.textContent).not.toBe(TRY_AGAIN_TEXT);
});

test("an accepted question waits for the answer its stream will carry", async () => {
  await boot();

  await ask();

  expect(query(".fr-typing")).not.toBeNull();
});

test("a question sent with no open stream never leaves, and settles at once", async () => {
  // Answer frames are live only
  connectStreamMock.mockReturnValue({ close: () => {}, isOpen: () => false });
  await boot();

  await ask();

  expect(sentMessage()).toBeUndefined();
  expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT);
});
