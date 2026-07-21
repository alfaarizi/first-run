// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { TRY_AGAIN_TEXT } from "./constants";
import { NudgeUi } from "./nudge";
import { MORPH_MS } from "./nudge.css";
import type { ChatSnapshot } from "./types";

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

function createUi(persist: (snapshot: ChatSnapshot) => void = () => {}, connected = true) {
  const callbacks = {
    onDismiss: vi.fn(),
    onEngage: vi.fn(),
    onSend: vi
      .fn<(id: string, text: string, ref?: string) => Promise<boolean>>()
      .mockResolvedValue(true),
  };
  const ui = new NudgeUi(callbacks, persist);
  // The steady state most tests exercise: the push channel is up, so the
  // composer accepts a question. The surface itself boots blocked.
  if (connected) ui.setConnected(true);
  const query = <T extends HTMLElement>(selector: string) =>
    shadow.querySelector<T>(selector);
  return { ui, callbacks, query };
}

/** Drives the composer to send one question, returning its message id. */
async function send(
  ui: ReturnType<typeof createUi>,
  text = "help",
): Promise<string> {
  ui.query<HTMLButtonElement>(".fr-launcher")?.click();
  const input = ui.query<HTMLTextAreaElement>(".fr-input");
  input!.value = text;
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await ui.callbacks.onSend.mock.results[0]?.value;
  return ui.callbacks.onSend.mock.calls[0]![0];
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

test("a nudge arriving after the panel opens keeps the opening nudge as the ref", () => {
  const { ui, callbacks, query } = createUi();
  ui.showNudge({ id: "n1", text: "Stuck on setup?" });
  query<HTMLButtonElement>(".fr-bubble-text")?.click();
  ui.showNudge({ id: "n2", text: "Stuck on billing?" });

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "how do I connect?";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  // n1's expansion opened the conversation, so the message refs n1 even though
  // n2 arrived first. Both nudges still report engaged.
  expect(callbacks.onSend).toHaveBeenCalledWith(expect.any(String), "how do I connect?", "n1");
  expect(callbacks.onEngage).toHaveBeenCalledWith("n1");
  expect(callbacks.onEngage).toHaveBeenCalledWith("n2");
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

  ui.notifyUnread();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(true);

  query<HTMLButtonElement>(".fr-launcher")?.click();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);

  ui.notifyUnread();
  expect(query(".fr-shell")?.classList.contains("fr-unread")).toBe(false);
});

test("a send renders the user text and a failed send releases the answer", async () => {
  const { callbacks, query } = createUi();
  callbacks.onSend.mockResolvedValue(false);
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "how do I connect?";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  expect(callbacks.onSend).toHaveBeenCalledWith(expect.any(String), "how do I connect?", undefined);
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

  const messageId = callbacks.onSend.mock.calls[0]![0];
  ui.appendAnswerToken(messageId, "Answer.");
  ui.finishAnswer(messageId, "Answer.", [
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

  ui.finishAnswer(callbacks.onSend.mock.calls[0]![0], undefined, []);

  expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT);
});

test("an answer idle past its window appends the retry line to partial text", async () => {
  vi.useFakeTimers();
  try {
    const { ui, callbacks, query } = createUi();
    query<HTMLButtonElement>(".fr-launcher")?.click();

    const input = query<HTMLTextAreaElement>(".fr-input");
    input!.value = "help";
    input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    await vi.waitFor(() => expect(callbacks.onSend).toHaveBeenCalled());

    ui.appendAnswerToken(callbacks.onSend.mock.calls[0]![0], "Half an answer");
    vi.advanceTimersByTime(35_000);

    // a half answer must never read as a finished one
    expect(query(".fr-message-agent .fr-body")?.textContent).toBe(`Half an answer ${TRY_AGAIN_TEXT}`);
  } finally {
    vi.useRealTimers();
  }
});

test("a send still pending past the idle window releases the retry line", () => {
  vi.useFakeTimers();
  try {
    const { callbacks, query } = createUi();
    // a stalled network request: the send never settles
    callbacks.onSend.mockReturnValueOnce(new Promise<boolean>(() => {}));
    query<HTMLButtonElement>(".fr-launcher")?.click();

    const input = query<HTMLTextAreaElement>(".fr-input");
    input!.value = "help";
    input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

    // the send has not landed, so no answer bubble has opened yet
    expect(query(".fr-message-agent")).toBeNull();

    vi.advanceTimersByTime(35_000);

    // the watchdog opens the answer itself so the question still gets the retry line
    expect(query(".fr-message-agent .fr-body")?.textContent).toBe(TRY_AGAIN_TEXT);
    expect(query(".fr-typing")).toBeNull();
  } finally {
    vi.useRealTimers();
  }
});

test("frames for another conversation's message never reach the answer", async () => {
  const { ui, callbacks, query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  const input = query<HTMLTextAreaElement>(".fr-input");
  input!.value = "help";
  input?.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await callbacks.onSend.mock.results[0]?.value;

  // another tab's answer, riding the same per-user stream
  ui.appendAnswerToken("other-message", "Their answer.");
  ui.finishAnswer("other-message", "Their answer.", []);

  const messageId = callbacks.onSend.mock.calls[0]![0];
  ui.appendAnswerToken(messageId, "Mine.");

  expect(query(".fr-message-agent .fr-body")?.textContent).toBe("Mine.");
});

test("the surface boots on no channel, so the composer waits and the header says so", () => {
  const { query } = createUi(() => {}, false);

  expect(query<HTMLTextAreaElement>(".fr-input")?.disabled).toBe(true);
  expect(query(".fr-subtitle")?.textContent).toBe("Connecting...");
});

test("a panel opened on a lost channel blocks, then takes the caret once it returns", () => {
  const { ui, query } = createUi();
  ui.setConnected(false);
  query<HTMLButtonElement>(".fr-launcher")?.click();
  const input = query<HTMLTextAreaElement>(".fr-input")!;

  // answers ride the channel, so opening while it is down cannot invite a question
  expect(input.disabled).toBe(true);
  expect(shadow.activeElement).not.toBe(input);

  ui.setConnected(true);
  expect(input.disabled).toBe(false);
  expect(query(".fr-subtitle")?.textContent).toBe("Here to help");
  expect(shadow.activeElement).toBe(input);
});

test("a pending answer shows the typing indicator until the first token", async () => {
  const ui = createUi();
  const messageId = await send(ui);

  expect(ui.query(".fr-typing")).not.toBeNull();

  ui.ui.appendAnswerToken(messageId, "Here");
  expect(ui.query(".fr-typing")).toBeNull();
  expect(ui.query(".fr-message-agent .fr-body")?.textContent).toBe("Here");
});

test("the answer's timestamp waits for the done frame, not the first token", async () => {
  const ui = createUi();
  const messageId = await send(ui);

  expect(ui.query(".fr-message-agent .fr-body.fr-pending")).not.toBeNull();

  // a half-written answer is not a finished message, so it stays unstamped
  ui.ui.appendAnswerToken(messageId, "Half");
  expect(ui.query(".fr-message-agent .fr-body.fr-pending")).not.toBeNull();

  ui.ui.finishAnswer(messageId, "Half of it.", []);
  expect(ui.query(".fr-message-agent .fr-body.fr-pending")).toBeNull();
  expect(ui.query(".fr-message-agent .fr-time")?.textContent).toMatch(/^\d{1,2}:\d{2}/);
});

test("the done frame heals text that a dropped token left behind", async () => {
  const ui = createUi();
  const messageId = await send(ui);

  // a reconnect dropped the middle of the stream, so only a tail arrived live
  ui.ui.appendAnswerToken(messageId, " 42.");

  // the server's full text reconciles the answer to the complete text
  ui.ui.finishAnswer(messageId, "The answer is 42.", []);

  expect(ui.query(".fr-message-agent .fr-body")?.textContent).toBe("The answer is 42.");
});

test("a send persists the settled transcript and the pending answer's id", async () => {
  let snapshot: ChatSnapshot | undefined;
  const ui = createUi((s) => (snapshot = structuredClone(s)));
  const messageId = await send(ui, "how do I connect?");
  dispatchEvent(new Event("pagehide"));

  expect(snapshot?.open).toBe(true);
  expect(snapshot?.pendingId).toBe(messageId);

  // The in-flight answer is not persisted. The done frame heals it on restore.
  expect(snapshot?.messages.map((m) => [m.who, m.text])).toEqual([["user", "how do I connect?"]]);
});

test("restore rebuilds the conversation and re-arms a pending answer", async () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  const messageId = await send(first, "how do I connect?");
  first.ui.appendAnswerToken(messageId, "Half");

  // a reload: the surface persists on pagehide, a fresh one restores the snapshot
  dispatchEvent(new Event("pagehide"));
  const second = createUi();
  second.ui.restore(snapshot);

  expect(second.query(".fr-message-user .fr-body")?.textContent).toBe("how do I connect?");

  // the answer was still streaming, so it returns to the typing state
  expect(second.query(".fr-typing")).not.toBeNull();

  // and the answer that completes after the reload lands in the restored answer
  second.ui.finishAnswer(messageId, "Open Settings.", []);
  expect(second.query(".fr-typing")).toBeNull();
  expect(second.query(".fr-message-agent .fr-body")?.textContent).toBe("Open Settings.");
});

test("a reload keeps the opening nudge as the reply's ref, even once a newer one lands", async () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  first.ui.showNudge({ id: "nudge-a", text: "Stuck on setup?" });
  first.query<HTMLButtonElement>(".fr-launcher")?.click();
  dispatchEvent(new Event("pagehide"));

  // the reload lands with the panel still open, then a newer nudge arrives
  // before the question is asked
  const second = createUi();
  second.ui.restore(snapshot);
  second.ui.showNudge({ id: "nudge-b", text: "Stuck on billing?" });

  // typed into the restored panel, so the newer nudge is still awaiting a reply
  const input = second.query<HTMLTextAreaElement>(".fr-input")!;
  input.value = "how do I connect?";
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await second.callbacks.onSend.mock.results[0]?.value;

  expect(second.callbacks.onSend.mock.calls[0]![2]).toBe("nudge-a");
});

test("a previewed nudge survives a reload and still reports its outcome", () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  first.ui.showNudge({ id: "n1", text: "Stuck on setup?" });

  // the server acked the nudge and advanced its cursor, so a reload will not
  // resend it: only the persisted snapshot can bring it back
  dispatchEvent(new Event("pagehide"));
  const second = createUi();
  second.ui.restore(snapshot);

  expect(second.query(".fr-bubble-text")?.textContent).toBe("Stuck on setup?");
  expect(second.query(".fr-shell")?.classList.contains("fr-unread")).toBe(true);

  // opening it after the reload still reports the engagement
  second.query<HTMLButtonElement>(".fr-bubble-text")?.click();
  expect(second.callbacks.onEngage).toHaveBeenCalledWith("n1");
});

test("an open-panel nudge still reports engaged when the reply lands after a reload", async () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  first.query<HTMLButtonElement>(".fr-launcher")?.click();
  first.ui.showNudge({ id: "n2", text: "Need a hand?" });

  dispatchEvent(new Event("pagehide"));
  const second = createUi();
  second.ui.restore(snapshot);

  // the nudge text is restored from the transcript
  expect(second.query(".fr-message-agent .fr-body")?.textContent).toBe("Need a hand?");

  // replying to it after the reload still reports it engaged
  const input = second.query<HTMLTextAreaElement>(".fr-input")!;
  input.value = "yes please";
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await second.callbacks.onSend.mock.results[0]?.value;

  expect(second.callbacks.onEngage).toHaveBeenCalledWith("n2");
});

test("a completed answer restores as plain text without a typing indicator", async () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  const messageId = await send(first);
  first.ui.appendAnswerToken(messageId, "Done.");
  first.ui.finishAnswer(messageId, "Done.", []);

  dispatchEvent(new Event("pagehide"));
  const second = createUi();
  second.ui.restore(snapshot);

  expect(second.query(".fr-typing")).toBeNull();
  expect(second.query(".fr-message-agent .fr-body")?.textContent).toBe("Done.");
});

test("a nudge arriving mid-answer survives a reload without corrupting the answer", async () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  const messageId = await send(first, "how do I connect?");

  // a nudge lands in the open panel while the answer is still streaming
  first.ui.showNudge({ id: "n1", text: "Need a hand?" });

  dispatchEvent(new Event("pagehide"));
  const second = createUi();
  second.ui.restore(snapshot);

  const agentText = () =>
    [...shadow.querySelectorAll(".fr-message-agent .fr-body")].map((b) => b.textContent);

  // the nudge keeps its text, and the answer is a distinct pending message
  expect(agentText()).toEqual(["Need a hand?", ""]);
  expect(second.query(".fr-typing")).not.toBeNull();

  // the answer heals into its own bubble, leaving the nudge untouched
  second.ui.finishAnswer(messageId, "Open Settings.", []);
  expect(agentText()).toEqual(["Need a hand?", "Open Settings."]);
});

test("the header close button collapses the panel without the morph", () => {
  const { query } = createUi();
  query<HTMLButtonElement>(".fr-launcher")?.click();

  query<HTMLButtonElement>(".fr-header .fr-close")?.click();

  expect(query(".fr-shell")?.classList.contains("fr-expanded")).toBe(false);
  expect(query(".fr-shell")?.classList.contains("fr-morph")).toBe(false);
  expect(shadow.activeElement).toBe(query(".fr-launcher"));
});

test("a half-typed draft is captured on refresh and restored", () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  first.query<HTMLButtonElement>(".fr-launcher")?.click();
  first.query<HTMLTextAreaElement>(".fr-input")!.value = "half a question";

  // typing alone never saves, the refresh's pagehide is what captures the draft
  dispatchEvent(new Event("pagehide"));
  expect(snapshot?.composerDraft).toBe("half a question");

  const second = createUi();
  second.ui.restore(snapshot);
  expect(second.query<HTMLTextAreaElement>(".fr-input")?.value).toBe("half a question");
});

test("the scroll offset persists only while open, and restore returns to it", async () => {
  let snapshot: ChatSnapshot | undefined;
  const first = createUi((s) => (snapshot = structuredClone(s)));
  first.query<HTMLButtonElement>(".fr-launcher")?.click();
  dispatchEvent(new Event("pagehide"));
  expect(typeof snapshot?.scrollTop).toBe("number");

  first.query<HTMLButtonElement>(".fr-header .fr-close")?.click();
  dispatchEvent(new Event("pagehide"));
  expect(snapshot?.scrollTop).toBeUndefined();

  // restore lands the message log back on the saved offset, one frame after the
  // expanded panel lays out
  const second = createUi();
  const log = second.query(".fr-messages")!;
  let scrollTop = 0;
  Object.defineProperty(log, "scrollTop", {
    get: () => scrollTop,
    set: (value: number) => (scrollTop = value),
    configurable: true,
  });
  second.ui.restore({ open: true, messages: [{ who: "user", text: "hi", at: 1 }], scrollTop: 200 });
  await new Promise((resolve) => requestAnimationFrame(resolve));
  expect(scrollTop).toBe(200);
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

test("the bot's typing bubble opens only once the send lands", async () => {
  const { callbacks, query } = createUi();
  let land!: (delivered: boolean) => void;
  callbacks.onSend.mockReturnValueOnce(new Promise((resolve) => (land = resolve)));

  query<HTMLButtonElement>(".fr-launcher")?.click();
  const input = query<HTMLTextAreaElement>(".fr-input")!;
  input.value = "help";
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));

  // the question is in, but the bot has not started typing yet
  expect(query(".fr-message-user .fr-body")?.textContent).toBe("help");
  expect(query(".fr-typing")).toBeNull();

  land(true);
  await Promise.resolve();
  await Promise.resolve();
  expect(query(".fr-typing")).not.toBeNull();
});

test("a reload returns the cursor to the composer when it held it before", async () => {
  const { ui, query } = createUi();
  ui.restore({
    open: true,
    messages: [{ who: "user", text: "hi", at: 1 }],
    scrollTop: 0,
    composerFocused: true,
  });

  await new Promise((resolve) => requestAnimationFrame(resolve));
  expect(shadow.activeElement).toBe(query(".fr-input"));
});

test("a reload leaves the caret alone when the composer did not hold it", async () => {
  const { ui, query } = createUi();
  // the caret sat in the host page's own input, so composerFocused is absent
  ui.restore({ open: true, messages: [{ who: "user", text: "hi", at: 1 }], scrollTop: 0 });

  await new Promise((resolve) => requestAnimationFrame(resolve));
  expect(shadow.activeElement).not.toBe(query(".fr-input"));
});

test("a focused composer persists that the caret was in it", () => {
  let snapshot: ChatSnapshot | undefined;
  const { query } = createUi((s) => (snapshot = structuredClone(s)));
  query<HTMLButtonElement>(".fr-launcher")?.click();
  query<HTMLTextAreaElement>(".fr-input")?.focus();

  dispatchEvent(new Event("pagehide"));
  expect(snapshot?.composerFocused).toBe(true);
});
