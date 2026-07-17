import { createChime } from "./chime";
import { createComposer, type Composer } from "./composer";
import { TRY_AGAIN_TEXT } from "./constants";
import { el } from "./dom";
import { createFace } from "./face";
import { FACE_CSS } from "./face.css";
import { MORPH_MS, NUDGE_CSS } from "./nudge.css";
import type { Citation, NudgePayload } from "./types";

// a stream silent this long is treated as dead, so its answer never blocks the next question
const ANSWER_IDLE_MS = 20_000;

// within this distance of the newest message the view still follows the stream
const NEAR_BOTTOM_PX = 40;

// built once, because Intl formatter construction dwarfs the per-message format call
const TIME_FORMAT = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });

export interface NudgeCallbacks {
  onDismiss(nudgeId: string): void;
  onEngage(nudgeId: string): void;
  onSend(text: string): Promise<boolean>;
}

/**
 * The widget surface: a persistent launcher whose shell expands into the
 * message panel, with nudges previewed in a bubble above it. Dynamic strings
 * land in textContent inside a closed shadow root, so server text stays text.
 */
export class NudgeUi {
  private readonly callbacks: NudgeCallbacks;
  private readonly playChime = createChime();
  private readonly root: HTMLElement;
  private readonly shell: HTMLElement;
  private readonly launcher: HTMLButtonElement;
  private readonly panel: HTMLElement;
  private readonly messages: HTMLElement;
  private readonly composer: Composer;
  private bubble: HTMLElement | undefined;
  private pendingNudge: NudgePayload | undefined;
  private readonly nudgesAwaitingReply = new Set<string>();
  private answer: HTMLElement | undefined;
  private answerTimer: number | undefined;
  private morphTimer: number | undefined;
  private expanded = false;

  constructor(callbacks: NudgeCallbacks) {
    this.callbacks = callbacks;

    this.launcher = el("button", "fr-launcher");
    this.launcher.setAttribute("aria-label", "FirstRun assistant");
    this.launcher.setAttribute("aria-expanded", "false");
    this.launcher.append(createFace());
    this.launcher.onclick = () => (this.expanded ? this.collapse() : this.open());

    this.messages = el("div", "fr-messages");
    this.messages.setAttribute("role", "log");
    this.messages.setAttribute("aria-label", "Messages");

    this.composer = createComposer(() => this.submit());

    const heading = el("div", "");
    heading.append(el("h2", "fr-title", "FirstRun"), el("p", "fr-subtitle", "Here to help"));

    const close = el("button", "fr-close", "×");
    close.setAttribute("aria-label", "Close");
    close.onclick = () => {
      this.collapse(false);
      this.launcher.focus({ preventScroll: true });
    };

    const header = el("div", "fr-header");
    header.append(heading, close);

    this.panel = el("div", "fr-panel");
    this.panel.append(header, this.messages, this.composer.root);

    const dot = el("span", "fr-dot");
    dot.setAttribute("aria-hidden", "true");

    // the launcher and dot follow the panel, so they stay clickable above it
    this.shell = el("div", "fr-shell");
    this.shell.append(this.panel, this.launcher, dot);

    this.root = el("div", "fr-root");
    this.root.append(this.shell);
    this.root.onkeydown = (e) => {
      if (e.key === "Escape" && this.expanded) {
        this.collapse();
        this.launcher.focus({ preventScroll: true });
      }
    };

    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = NUDGE_CSS + FACE_CSS;
    shadow.append(style, this.root);

    if (document.body) document.body.append(host);
    else addEventListener("DOMContentLoaded", () => document.body.append(host));
  }

  /** Where the confirmation card mounts, slotted between the messages and the input. */
  get container(): HTMLElement {
    return this.panel;
  }

  /** Returns the surface to a fresh collapsed launcher, used when the end user changes. */
  reset(): void {
    this.dropAnswer();
    this.removeBubble();
    this.messages.replaceChildren();
    this.panel.querySelector(".fr-confirm")?.remove();
    this.composer.clear();
    this.shell.classList.remove("fr-unread");
    this.collapse(false);
  }

  /** Announces a frame the collapsed shell would otherwise hide, with the dot and chime. */
  notify(): void {
    if (this.expanded) return;
    this.shell.classList.add("fr-unread");
    this.playChime();
  }

  showNudge(nudge: NudgePayload): void {
    if (this.expanded) {
      // an open panel already shows the conversation, so the nudge joins it
      // and the user's next reply reports it engaged
      this.appendMessage("agent", nudge.text);
      this.nudgesAwaitingReply.add(nudge.id);
      return;
    }

    // a different nudge displacing the preview joins the conversation, because
    // the server has claimed it and will never push it again
    const displaced = this.pendingNudge;
    if (displaced && displaced.id !== nudge.id) {
      this.appendMessage("agent", displaced.text);
      this.nudgesAwaitingReply.add(displaced.id);
    }
    this.removeBubble();
    this.pendingNudge = nudge;

    const open = el("button", "fr-bubble-text", nudge.text);
    open.onclick = () => this.open();

    // the multiplication sign, the conventional close glyph
    const dismiss = el("button", "fr-close", "×");
    dismiss.setAttribute("aria-label", "Dismiss");
    dismiss.onclick = () => {
      this.removeBubble();
      this.shell.classList.remove("fr-unread");
      this.callbacks.onDismiss(nudge.id);
    };

    const bubble = el("div", "fr-bubble");
    bubble.setAttribute("role", "status");
    bubble.append(open, dismiss);
    this.bubble = bubble;
    this.root.prepend(bubble);
    this.notify();
  }

  appendToken(text: string): void {
    if (!this.answer) return;
    const follow = this.isAtBottom();
    this.answer.append(text);
    if (follow) this.scrollToBottom();
    this.keepAnswerAlive();
  }

  finishAnswer(citations: Citation[]): void {
    if (this.answer) {
      const follow = this.isAtBottom();
      const list = el("ul", "fr-citations");
      for (const citation of citations) {
        const url = sanitizeUrl(citation.url);
        if (!url) continue;

        const link = el("a", "", citation.title);
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";

        const item = el("li", "");
        item.append(link);
        list.append(item);
      }
      if (list.childElementCount > 0) this.answer.append(list);
      // a stream that ends having said nothing reads as a failure
      else if (!this.answer.textContent) this.answer.textContent = TRY_AGAIN_TEXT;
      if (follow) this.scrollToBottom();
    }
    this.dropAnswer();
  }

  /** Opens the panel, engaging any previewed nudge into the conversation. */
  private open(): void {
    const nudge = this.pendingNudge;
    if (nudge) {
      this.removeBubble();
      this.appendMessage("agent", nudge.text);
      this.callbacks.onEngage(nudge.id);
    }
    this.morph();
    this.expanded = true;
    this.shell.classList.add("fr-expanded");
    this.shell.classList.remove("fr-unread");
    this.launcher.setAttribute("aria-expanded", "true");
    this.composer.focus();
  }

  // the conversation keeps its DOM, so reopening restores it
  private collapse(animate = true): void {
    if (animate) this.morph();
    else {
      // cancel any running morph, so the close lands instantly
      clearTimeout(this.morphTimer);
      this.shell.classList.remove("fr-morph");
    }
    this.expanded = false;
    this.shell.classList.remove("fr-expanded");
    this.launcher.setAttribute("aria-expanded", "false");
    // closing without a reply leaves the nudges to the ignored outcome
    this.nudgesAwaitingReply.clear();
  }

  // a permanent transition would also animate viewport-driven size changes on
  // resize, so it exists only while the shell morphs between its two sizes
  private morph(): void {
    clearTimeout(this.morphTimer);
    this.shell.classList.add("fr-morph");
    this.morphTimer = setTimeout(() => this.shell.classList.remove("fr-morph"), MORPH_MS);
  }

  private removeBubble(): void {
    this.bubble?.remove();
    this.bubble = this.pendingNudge = undefined;
  }

  private submit(): void {
    const text = this.composer.text();
    if (!text || this.answer) return;

    this.composer.clear();
    this.appendMessage("user", text);
    for (const id of this.nudgesAwaitingReply) this.callbacks.onEngage(id);
    this.nudgesAwaitingReply.clear();
    const answer = this.appendMessage("agent");
    this.answer = answer;
    this.keepAnswerAlive();

    void this.callbacks
      .onSend(text)
      .catch(() => false)
      .then((delivered) => {
        // a failed send frees the slot, so the user can try again
        if (!delivered && this.answer === answer) {
          answer.textContent = TRY_AGAIN_TEXT;
          this.dropAnswer();
        }
      });
  }

  /** Appends a bubble and returns its body, the element streaming writes into. */
  private appendMessage(kind: "user" | "agent", text = ""): HTMLElement {
    const message = el("div", `fr-message fr-message-${kind}`);
    const body = el("span", "fr-body", text);
    message.append(body, el("span", "fr-time", TIME_FORMAT.format(Date.now())));
    this.messages.append(message);
    this.scrollToBottom();
    return body;
  }

  private isAtBottom(): boolean {
    const m = this.messages;
    return m.scrollHeight - m.scrollTop - m.clientHeight < NEAR_BOTTOM_PX;
  }

  private scrollToBottom(): void {
    this.messages.scrollTo(0, this.messages.scrollHeight);
  }

  // resets the idle countdown
  private keepAnswerAlive(): void {
    clearTimeout(this.answerTimer);
    this.answerTimer = setTimeout(() => {
      if (this.answer?.isConnected && !this.answer.textContent) {
        this.answer.textContent = TRY_AGAIN_TEXT;
      }
      this.dropAnswer();
    }, ANSWER_IDLE_MS);
  }

  // stops the idle countdown and frees the slot
  private dropAnswer(): void {
    clearTimeout(this.answerTimer);
    this.answer = undefined;
  }
}

/** Allows http and https only, so a hostile stream cannot mint javascript links. */
function sanitizeUrl(raw: string): string | undefined {
  try {
    const url = new URL(raw, location.href);
    if (url.protocol === "https:" || url.protocol === "http:") return url.href;
  } catch {
    // not a url
  }
  return undefined;
}
