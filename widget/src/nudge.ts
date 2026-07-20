import { createChime } from "./chime";
import { createComposer, type Composer } from "./composer";
import { TRY_AGAIN_TEXT } from "./constants";
import { el } from "./dom";
import { createFace } from "./face";
import { FACE_CSS } from "./face.css";
import { MORPH_MS, NUDGE_CSS } from "./nudge.css";
import type { Citation, NudgePayload } from "./types";
import { uuidv7 } from "./uuidv7";

// Past the server's 30s per-answer watchdog. A live stream always closes the
// answer first, so this fires only when the stream itself is dead.
const ANSWER_IDLE_MS = 35_000;

// Within this distance of the newest message the view still follows the stream.
const NEAR_BOTTOM_PX = 40;

// Built once, because Intl formatter construction dwarfs the per-message format call.
const TIME_FORMAT = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });

/** What the surface reports back: nudge outcomes and outbound messages. */
export interface NudgeCallbacks {
  /** Reports the nudge dismissed from its preview bubble. */
  onDismiss(nudgeId: string): void;
  /** Reports the nudge engaged, by opening the panel or by replying. */
  onEngage(nudgeId: string): void;
  /**
   * Sends one user message. `id` ties the answer's stream frames back to
   * this message, and `ref` names the nudge this message answers, when the
   * panel opened from one.
   */
  onSend(id: string, text: string, ref?: string): Promise<boolean>;
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
  private lastEngagedNudge: string | undefined;
  private answer: HTMLElement | undefined;
  private answerMessageId: string | undefined;
  private answerTimer: number | undefined;
  private morphTimer: number | undefined;
  private expanded = false;

  /** Builds the collapsed surface and mounts it in a closed shadow root. */
  constructor(callbacks: NudgeCallbacks) {
    this.callbacks = callbacks;
    this.launcher = this.buildLauncher();
    this.messages = this.buildMessages();
    this.composer = createComposer(() => this.submit());
    this.panel = this.buildPanel();
    this.shell = this.buildShell();
    this.root = this.buildRoot();
    this.mount();
  }

  /** Where the confirmation card mounts, slotted between the messages and the input. */
  get container(): HTMLElement {
    return this.panel;
  }

  /** Returns the surface to a fresh collapsed launcher, used when the end user changes. */
  reset(): void {
    this.dropAnswer();
    this.removeBubble();
    this.lastEngagedNudge = undefined;
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

  /** Shows the nudge: into the conversation when expanded, else as the preview bubble. */
  showNudge(nudge: NudgePayload): void {
    if (this.expanded) {
      // An open panel already shows the conversation, so the nudge joins it.
      // The user's next reply reports it engaged.
      this.appendMessage("agent", nudge.text);
      this.nudgesAwaitingReply.add(nudge.id);
      return;
    }

    // A nudge displaced from the preview joins the conversation instead.
    // The server has claimed it and will never push it again.
    const displaced = this.pendingNudge;
    if (displaced && displaced.id !== nudge.id) {
      this.appendMessage("agent", displaced.text);
      this.nudgesAwaitingReply.add(displaced.id);
    }
    this.removeBubble();
    this.pendingNudge = nudge;
    this.bubble = this.buildBubble(nudge);
    this.root.prepend(this.bubble);
    this.notify();
  }

  /** Streams one answer span into the slot awaiting its message id. */
  appendToken(messageId: string, text: string): void {
    // Another tab's answer rides the same user stream. Only frames for the
    // question this panel sent reach its slot.
    if (!this.answer || messageId !== this.answerMessageId) return;
    const follow = this.isAtBottom();
    this.answer.append(text);
    if (follow) this.scrollToBottom();
    this.keepAnswerAlive();
  }

  /** Closes the streamed answer, rendering its citations as links. */
  finishAnswer(messageId: string, citations: Citation[]): void {
    if (messageId !== this.answerMessageId) return;
    if (this.answer) {
      const follow = this.isAtBottom();
      const list = buildCitations(citations);
      if (list.childElementCount > 0) this.answer.append(list);
      // A stream that ends having said nothing reads as a failure.
      else if (!this.answer.textContent) this.answer.textContent = TRY_AGAIN_TEXT;
      if (follow) this.scrollToBottom();
    }
    this.dropAnswer();
  }

  /** Builds the launcher button that toggles between the two shell states. */
  private buildLauncher(): HTMLButtonElement {
    const launcher = el("button", "fr-launcher");
    launcher.setAttribute("aria-label", "FirstRun assistant");
    launcher.setAttribute("aria-expanded", "false");
    launcher.append(createFace());
    launcher.onclick = () => (this.expanded ? this.collapse() : this.open());
    return launcher;
  }

  /** Builds the scrollable message log. */
  private buildMessages(): HTMLElement {
    const messages = el("div", "fr-messages");
    messages.setAttribute("role", "log");
    messages.setAttribute("aria-label", "Messages");
    return messages;
  }

  /** Builds the expanded panel: header, message log, then the composer. */
  private buildPanel(): HTMLElement {
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

    const panel = el("div", "fr-panel");
    panel.append(header, this.messages, this.composer.root);
    return panel;
  }

  /** Builds the shell that morphs between the launcher and the panel. */
  private buildShell(): HTMLElement {
    const dot = el("span", "fr-dot");
    dot.setAttribute("aria-hidden", "true");

    // The launcher and dot follow the panel, so they stay clickable above it.
    const shell = el("div", "fr-shell");
    shell.append(this.panel, this.launcher, dot);
    return shell;
  }

  /** Builds the root that hosts everything and closes the panel on Escape. */
  private buildRoot(): HTMLElement {
    const root = el("div", "fr-root");
    root.append(this.shell);
    root.onkeydown = (e) => {
      if (e.key === "Escape" && this.expanded) {
        this.collapse();
        this.launcher.focus({ preventScroll: true });
      }
    };
    return root;
  }

  /** Builds the preview bubble: the nudge text beside its dismiss button. */
  private buildBubble(nudge: NudgePayload): HTMLElement {
    const open = el("button", "fr-bubble-text", nudge.text);
    open.onclick = () => this.open();

    // The multiplication sign, the conventional close glyph.
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
    return bubble;
  }

  /** Mounts the surface under document.body, waiting for it on early loads. */
  private mount(): void {
    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = NUDGE_CSS + FACE_CSS;
    shadow.append(style, this.root);

    if (document.body) document.body.append(host);
    else addEventListener("DOMContentLoaded", () => document.body.append(host));
  }

  /** Opens the panel, engaging any previewed nudge into the conversation. */
  private open(): void {
    const nudge = this.pendingNudge;
    if (nudge) {
      this.removeBubble();
      this.appendMessage("agent", nudge.text);
      this.lastEngagedNudge = nudge.id;
      this.callbacks.onEngage(nudge.id);
    }
    this.morph();
    this.expanded = true;
    this.shell.classList.add("fr-expanded");
    this.shell.classList.remove("fr-unread");
    this.launcher.setAttribute("aria-expanded", "true");
    this.composer.focus();
  }

  /** Collapses to the launcher. The conversation keeps its DOM, so reopening restores it. */
  private collapse(animate = true): void {
    if (animate) this.morph();
    else {
      // Cancel any running morph, so the close lands instantly.
      clearTimeout(this.morphTimer);
      this.shell.classList.remove("fr-morph");
    }
    this.expanded = false;
    this.shell.classList.remove("fr-expanded");
    this.launcher.setAttribute("aria-expanded", "false");
    // Closing without a reply leaves the nudges to the ignored outcome.
    this.nudgesAwaitingReply.clear();
  }

  /**
   * Animates the shell between its two sizes. A permanent transition would
   * also animate viewport-driven size changes on resize, so it exists only
   * while the shell morphs.
   */
  private morph(): void {
    clearTimeout(this.morphTimer);
    this.shell.classList.add("fr-morph");
    this.morphTimer = setTimeout(() => this.shell.classList.remove("fr-morph"), MORPH_MS);
  }

  /** Removes the preview bubble and forgets its nudge. */
  private removeBubble(): void {
    this.bubble?.remove();
    this.bubble = this.pendingNudge = undefined;
  }

  /** Sends the drafted message and opens the slot its answer streams into. */
  private submit(): void {
    const text = this.composer.text();
    if (!text || this.answer) return;

    this.composer.clear();
    this.appendMessage("user", text);
    // Bind the message to the nudge that opened the conversation. Fall back to
    // a nudge that arrived while it was open only when the launcher, not a
    // nudge, opened it, so a later nudge never displaces the opener's ref.
    const awaiting = this.nudgesAwaitingReply.values().next().value;
    if (awaiting && !this.lastEngagedNudge) this.lastEngagedNudge = awaiting;
    const ref = this.lastEngagedNudge;
    for (const id of this.nudgesAwaitingReply) this.callbacks.onEngage(id);
    this.nudgesAwaitingReply.clear();
    const id = uuidv7();
    const answer = this.appendMessage("agent");
    this.answer = answer;
    this.answerMessageId = id;
    this.keepAnswerAlive();

    void this.callbacks
      .onSend(id, text, ref)
      .catch(() => false)
      .then((delivered) => {
        // A failed send frees the slot, so the user can try again.
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

  /** Reports whether the view sits close enough to the bottom to follow the stream. */
  private isAtBottom(): boolean {
    const m = this.messages;
    return m.scrollHeight - m.scrollTop - m.clientHeight < NEAR_BOTTOM_PX;
  }

  /** Scrolls the message log to its newest message. */
  private scrollToBottom(): void {
    this.messages.scrollTo(0, this.messages.scrollHeight);
  }

  /** Resets the idle countdown that degrades a stalled answer to the retry line. */
  private keepAnswerAlive(): void {
    clearTimeout(this.answerTimer);
    this.answerTimer = setTimeout(() => {
      if (this.answer?.isConnected) {
        // The retry line joins any partial text, so a half answer never reads as done.
        this.answer.append(this.answer.textContent ? ` ${TRY_AGAIN_TEXT}` : TRY_AGAIN_TEXT);
      }
      this.dropAnswer();
    }, ANSWER_IDLE_MS);
  }

  /** Stops the idle countdown and frees the answer slot. */
  private dropAnswer(): void {
    clearTimeout(this.answerTimer);
    this.answer = this.answerMessageId = undefined;
  }
}

/** Builds the citation list, dropping any citation without a safe link. */
function buildCitations(citations: Citation[]): HTMLElement {
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
  return list;
}

/** Allows http and https only, so a hostile stream cannot mint javascript links. */
function sanitizeUrl(raw: string): string | undefined {
  try {
    const url = new URL(raw, location.href);
    if (url.protocol === "https:" || url.protocol === "http:") return url.href;
  } catch {
    // Not a URL.
  }
  return undefined;
}
