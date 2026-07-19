// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { TRY_AGAIN_TEXT } from "./constants";
import { NudgeUi } from "./nudge";
import { MORPH_MS } from "./nudge.css";

const attachShadow = HTMLElement.prototype.attachShadow;
let shadow: ShadowRoot;

// jsdom leaves scroll methods unimplemented
Element.prototype.scrollTo = () => {};

beforeEach(() => {
  document.body.replaceChildren();
  // the widget closes its shadow root, so tests reopen it to observe
  vi.spyOn(HTMLElement.prototype, "attachShadow").mockImplementation(function (
    this: HTMLElement,
    init: ShadowRootInit,
  ) {
    shadow = attachShadow.call(this, { ...init, mode: "open" });
    return shadow;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

function createUi() {
  const callbacks = {
    onDismiss: vi.fn(),
    onEngage: vi.fn(),
    onSend: vi.fn<(text: string) => Promise<boolean>>().mockResolvedValue(true),
  };
  const ui = new NudgeUi(callbacks);
  const query = <T extends HTMLElement>(selector: string) =>
    shadow.querySelector<T>(selector);
  return { ui, callbacks, query };
}

test("boots as a collapsed launcher with the face", () => {
  const { query } = createUi();

  const launcher = query<HTMLButtonElement>(".fr-launcher");
  expect(launcher?.getAttribute("aria-expanded")).toBe("false");
  expect(shadow.querySelectorAll(".fr-eye")).toHaveLength(2);
  expect(query(".fr-title")?.textContent).toBe("FirstRun");
  expect(query(".fr-subtitle")?.textContent).toBe("Here to help");
  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(false);
});

test("a nudge previews in a bubble and marks the shell unread", () => {
  const { ui, query } = createUi();

  ui.showNudge({ id: "n1", text: "Stuck on setup?" });

  expect(query(".fr-bubble-text")?.textContent).toBe("Stuck on setup?");
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(true);
});

test("dismissing the bubble reports dismissed and clears the preview", () => {
  const { ui, callbacks, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck?" });

  query<HTMLButtonElement>(".fr-bubble .fr-close")?.click();

  expect(callbacks.onDismiss).toHaveBeenCalledWith("n1");
  expect(query(".fr-bubble")).toBeNull();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);
});

test("opening from the bubble engages the nudge into the conversation", () => {
  const { ui, callbacks, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck?" });

  query<HTMLButtonElement>(".fr-bubble-text")?.click();

  expect(callbacks.onEngage).toHaveBeenCalledWith("n1");
  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(true);
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);
  expect(query(".fr-launcher")?.getAttribute("aria-expanded")).toBe("true");
  expect(query(".fr-bubble")).toBeNull();
  expect(query(".fr-message-agent .fr-body")?.textContent).toBe("Stuck?");
});

test("the launcher engages a pending nudge and toggles the panel", () => {
  const { ui, callbacks, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck?" });

  const launcher = query<HTMLButtonElement>(".fr-launcher");
  launcher?.click();
  expect(callbacks.onEngage).toHaveBeenCalledWith("n1");

  launcher?.click();
  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(false);
  expect(callbacks.onDismiss).not.toHaveBeenCalled();
});

test("the morph transition unhooks after the toggle, so resizes stay instant", () => {
  vi.useFakeTimers();
  const { query } = createUi();

  query<HTMLButtonElement>(".fr-launcher")?.click();
  expect(query(".fr-shell")?.classList.contains("fr-morph")).toBe(true);

  vi.advanceTimersByTime(MORPH_MS);
  expect(query(".fr-shell")?.classList.contains("fr-morph")).toBe(false);
  vi.useRealTimers();
});

test("escape collapses the panel and returns focus to the launcher", () => {
  const { query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  query<HTMLTextAreaElement>(".fr-input")?.dispatchEvent(
    new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
  );

  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(false);
  expect(shadow.activeElement).toBe(query(".fr-launcher"));
});

test("a nudge with the panel open joins the conversation without an outcome", () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  ui.showNudge({ id: "n2", text: "Try connecting a source." });

  expect(query(".fr-message-agent .fr-body")?.textContent).toBe("Try connecting a source.");
  expect(query(".fr-bubble")).toBeNull();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);
  expect(callbacks.onEngage).not.toHaveBeenCalled();
});

test("replying after an open-panel nudge reports it engaged once", () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();
  ui.showNudge({ id: "n2", text: "Stuck?" });

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "yes, how do I connect?";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onEngage).toHaveBeenCalledWith("n2");
  expect(callbacks.onEngage).toHaveBeenCalledTimes(1);
});

test("a nudge displaced from the bubble keeps its text and outcome", () => {
  const { ui, callbacks, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck?" });
  ui.showNudge({ id: "n2", text: "Try connecting a source." });

  // the newest nudge owns the bubble, the displaced one joins the conversation
  expect(query(".fr-bubble-text")?.textContent).toBe("Try connecting a source.");
  expect(query(".fr-message-agent .fr-body")?.textContent).toBe("Stuck?");

  query<HTMLButtonElement>(".fr-bubble-text")?.click();
  expect(callbacks.onEngage).toHaveBeenCalledWith("n2");

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "how do I connect?";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onEngage).toHaveBeenCalledWith("n1");
});

test("a redelivered pending nudge duplicates neither its text nor its outcome", () => {
  const { ui, callbacks, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck?" });
  ui.showNudge({ id: "n1", text: "Stuck?" });

  expect(query(".fr-message-agent")).toBeNull();

  query<HTMLButtonElement>(".fr-bubble-text")?.click();
  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "hello";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onEngage).toHaveBeenCalledTimes(1);
});

test("one reply engages every open-panel nudge awaiting it", () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();
  ui.showNudge({ id: "n2", text: "Stuck?" });
  ui.showNudge({ id: "n3", text: "Try connecting a source." });

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "yes, how do I connect?";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onEngage).toHaveBeenCalledWith("n2");
  expect(callbacks.onEngage).toHaveBeenCalledWith("n3");
  expect(callbacks.onEngage).toHaveBeenCalledTimes(2);
});

test("a redelivered open-panel nudge reports engaged once", () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();
  ui.showNudge({ id: "n2", text: "Stuck?" });
  ui.showNudge({ id: "n2", text: "Stuck?" });

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "hello";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onEngage).toHaveBeenCalledWith("n2");
  expect(callbacks.onEngage).toHaveBeenCalledTimes(1);
});

test("closing the panel before a reply leaves an open-panel nudge unengaged", () => {
  const { ui, callbacks, query } = createUi();
  const launcher = query<HTMLButtonElement>(".fr-launcher");
  launcher?.click();
  ui.showNudge({ id: "n2", text: "Stuck?" });
  launcher?.click();
  launcher?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "hello";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onEngage).not.toHaveBeenCalled();
});

test("notify marks the shell unread only while collapsed", () => {
  const { ui, query } = createUi();

  ui.notify();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(true);

  query<HTMLButtonElement>(".fr-launcher")?.click();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);

  ui.notify();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);
});

test("a send renders the user text and a failed send frees the slot", async () => {
  const { callbacks, query } = createUi();
  callbacks.onSend.mockResolvedValue(false);
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "how do I connect?";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onSend).toHaveBeenCalledWith("how do I connect?", undefined);
  expect(query(".fr-message-user .fr-body")?.textContent).toBe("how do I connect?");
  expect(query(".fr-message-user .fr-time")?.textContent).toMatch(/^\d{1,2}:\d{2}/);

  await vi.waitFor(() =>
    expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT),
  );
});

test("a rejected send reads as a failed delivery", async () => {
  const { callbacks, query } = createUi();
  callbacks.onSend.mockRejectedValue(new Error("network down"));
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "help";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  await vi.waitFor(() =>
    expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT),
  );
});

test("shift+enter keeps composing instead of sending", () => {
  const { callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "first line";
  input?.dispatchEvent(
    new KeyboardEvent("keydown", { key: "Enter", shiftKey: true, bubbles: true }),
  );

  expect(callbacks.onSend).not.toHaveBeenCalled();
  expect(input?.value).toBe("first line");
});

test("enter that commits IME composition keeps composing instead of sending", () => {
  const { callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "日本語";
  input?.dispatchEvent(
    new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true }),
  );

  expect(callbacks.onSend).not.toHaveBeenCalled();
  expect(input?.value).toBe("日本語");
});

test("the send button arms only when the draft has visible text", () => {
  const { query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();
  const input = query<HTMLTextAreaElement>(".fr-input");
  const send = query<HTMLButtonElement>(".fr-send");

  input!.value = "\n\n";
  input?.dispatchEvent(new Event("input"));
  expect(send?.classList.contains("fr-send-ready")).toBe(false);

  input!.value = "\nhello";
  input?.dispatchEvent(new Event("input"));
  expect(send?.classList.contains("fr-send-ready")).toBe(true);
});

test("citations render http links only", async () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "help";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await callbacks.onSend.mock.results[0]?.value;

  ui.appendToken("Answer.");
  ui.finishAnswer([
    { title: "bad", url: "javascript:alert(1)" },
    { title: "docs", url: "https://docs.example.com/setup" },
  ]);

  const links = shadow.querySelectorAll<HTMLAnchorElement>(".fr-citations a");
  expect(links).toHaveLength(1);
  expect(links[0]?.href).toBe("https://docs.example.com/setup");
  expect(links[0]?.rel).toBe("noopener noreferrer");
});

test("an answer that ends without tokens or citations reads as a failure", async () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "help";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await callbacks.onSend.mock.results[0]?.value;

  ui.finishAnswer([]);

  expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT);
});

test("the header close button collapses the panel without the morph", () => {
  const { query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  query<HTMLButtonElement>(".fr-header .fr-close")?.click();

  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(false);
  expect(query(".fr-shell")?.classList.contains("fr-morph")).toBe(false);
  expect(shadow.activeElement).toBe(query(".fr-launcher"));
});

test("reset returns the surface to a fresh collapsed launcher", () => {
  const { ui, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck?" });
  query<HTMLButtonElement>(".fr-launcher")?.click();

  ui.reset();

  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(false);
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);
  expect(query(".fr-bubble")).toBeNull();
  expect(query(".fr-messages")?.childElementCount).toBe(0);
});
