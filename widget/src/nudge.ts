import { createChime, type Chime } from "./chime";
import { createComposer, type Composer } from "./composer";
import { TRY_AGAIN_TEXT } from "./constants";
import { el } from "./dom";
import { createFace } from "./face";
import { FACE_CSS } from "./face.css";
import { MORPH_MS, NUDGE_CSS } from "./nudge.css";
import type { ChatMessage, ChatSnapshot, Citation, NudgePayload } from "./types";
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

/** Records the surface's state so a reload restores the conversation. */
export type PersistChat = (snapshot: ChatSnapshot) => void;

/**
 * The widget surface: a persistent launcher whose shell expands into the
 * message panel, with nudges previewed in a bubble above it. Dynamic strings
 * land in textContent inside a closed shadow root, so server text stays text.
 */
export class NudgeUi {
  private readonly callbacks: NudgeCallbacks;
  private readonly persist: PersistChat;
  private readonly chime: Chime;
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
  // The rendered transcript, mirrored so a reload can rebuild it from storage.
  private transcript: ChatMessage[] = [];
  private answer: HTMLElement | undefined;
  private answerModel: ChatMessage | undefined;
  private answerMessageId: string | undefined;
  private typing: HTMLElement | undefined;
  private answerTimer: number | undefined;
  private morphTimer: number | undefined;
  private expanded = false;

  /** Builds the collapsed surface and mounts it in a closed shadow root. */
  constructor(callbacks: NudgeCallbacks, persist: PersistChat = () => {}) {
    this.callbacks = callbacks;
    this.persist = persist;
    this.chime = createChime();
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
    this.transcript = [];
    this.messages.replaceChildren();
    this.panel.querySelector(".fr-confirm")?.remove();
    this.composer.clear();
    this.shell.classList.remove("fr-unread");
    this.collapse(false);
  }

  /**
   * Rebuilds the conversation a prior page left in storage, so a reload keeps
   * the transcript, the open panel, and any answer still being processed.
   */
  restore(snapshot: ChatSnapshot | undefined): void {
    if (!snapshot) return;
    this.transcript = snapshot.messages.map((message) => ({
      ...message,
      citations: message.citations && [...message.citations],
    }));

    // Restored messages arrive at once, so they skip the one-by-one entry rise.
    for (const message of this.transcript) this.renderMessage(message, false);

    // A prior page left mid-answer: open a fresh slot; the buffered done frame
    // fills it when the stream reopens.
    if (snapshot.pendingId) this.openAnswerSlot(snapshot.pendingId);

    if (snapshot.open) {
      this.expanded = true;
      this.shell.classList.add("fr-expanded");
      this.launcher.setAttribute("aria-expanded", "true");
    }
    this.scrollToBottom();
  }

  /** Announces a frame the collapsed shell would otherwise hide, with the dot and chime. */
  notify(): void {
    if (this.expanded) return;
    this.shell.classList.add("fr-unread");
    this.chime.nudge();
  }

  /** Shows the nudge: into the conversation when expanded, else as the preview bubble. */
  showNudge(nudge: NudgePayload): void {
    if (this.expanded) {
      // The open panel joins the nudge into the conversation; the next reply
      // reports it engaged.
      this.appendMessage("agent", nudge.text);
      this.nudgesAwaitingReply.add(nudge.id);
      this.save();
      return;
    }

    // A nudge displaced from the preview joins the conversation instead. The
    // server has claimed it and never pushes it again.
    const displaced = this.pendingNudge;
    if (displaced && displaced.id !== nudge.id) {
      this.appendMessage("agent", displaced.text);
      this.nudgesAwaitingReply.add(displaced.id);
      this.save();
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
    this.clearTyping();

    // The model text is reconciled once from the done frame, not built per token.
    this.answer.append(text);

    if (follow) this.scrollToBottom();
    this.keepAnswerAlive();
  }

  /** Closes the streamed answer, reconciling to the server's final text and citations. */
  finishAnswer(messageId: string, text: string | undefined, citations: Citation[]): void {
    if (messageId !== this.answerMessageId) return;
    const body = this.answer;
    if (body) {
      const follow = this.isAtBottom();
      this.clearTyping();

      // Reconcile to the authoritative done text, healing any dropped token.
      const streamed = body.textContent;
      const finalText = text || streamed || TRY_AGAIN_TEXT;
      if (streamed !== finalText) body.textContent = finalText;
      if (this.answerModel) {
        this.answerModel.text = finalText;
        this.answerModel.citations = citations;
      }

      const list = buildCitations(citations);
      if (list.childElementCount > 0) body.append(list);
      if (follow) this.scrollToBottom();
    }
    this.dropAnswer();
    this.save();
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
    this.save();
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

    this.save();
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
    this.chime.send();
    this.appendMessage("user", text);
    const ref = this.claimReplyRef();

    const id = uuidv7();
    const slot = this.openAnswerSlot(id);
    this.save();

    void this.callbacks
      .onSend(id, text, ref)
      .catch(() => false)
      .then((delivered) => {
        // A failed send frees the slot, so the user can try again.
        if (!delivered && this.answer === slot) {
          this.clearTyping();
          slot.textContent = TRY_AGAIN_TEXT;
          if (this.answerModel) this.answerModel.text = TRY_AGAIN_TEXT;
          this.dropAnswer();
          this.save();
        }
      });
  }

  /**
   * Resolves the nudge this reply answers and engages every awaiting nudge. A
   * nudge that arrived meanwhile is the ref only when the launcher opened the
   * panel, so a later nudge never displaces the opener's ref.
   */
  private claimReplyRef(): string | undefined {
    const awaiting = this.nudgesAwaitingReply.values().next().value;
    if (awaiting && !this.lastEngagedNudge) this.lastEngagedNudge = awaiting;
    for (const id of this.nudgesAwaitingReply) this.callbacks.onEngage(id);
    this.nudgesAwaitingReply.clear();
    return this.lastEngagedNudge;
  }

  /** Opens the agent bubble one answer streams into, tracked by its message id. */
  private openAnswerSlot(messageId: string): HTMLElement {
    // It arrives showing the typing indicator, so it never takes the entry rise.
    const { model, body } = this.appendMessage("agent", "", false);
    this.answer = body;
    this.answerModel = model;
    this.answerMessageId = messageId;
    this.showTyping();
    this.keepAnswerAlive();
    return body;
  }

  /** Appends one message to the transcript and the log, returning its model and body. */
  private appendMessage(
    who: "user" | "agent",
    text = "",
    animate = true,
  ): { model: ChatMessage; body: HTMLElement } {
    const model: ChatMessage = { who, text, at: Date.now() };
    this.transcript.push(model);
    return { model, body: this.renderMessage(model, animate) };
  }

  /** Renders one message into the log, returning the body streaming writes into. */
  private renderMessage(message: ChatMessage, animate = true): HTMLElement {
    const bubble = el("div", `fr-message fr-message-${message.who}`);
    if (!animate) bubble.classList.add("fr-no-anim");
    const body = el("span", "fr-body", message.text);
    if (message.citations?.length) {
      const list = buildCitations(message.citations);
      if (list.childElementCount > 0) body.append(list);
    }
    bubble.append(body, el("span", "fr-time", TIME_FORMAT.format(message.at)));
    this.messages.append(bubble);
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

  /** Shows the typing indicator in the answer slot until text arrives. */
  private showTyping(): void {
    if (!this.answer || this.typing) return;
    this.typing = buildTyping();
    this.answer.append(this.typing);
  }

  /** Removes the typing indicator once the answer streams in or closes. */
  private clearTyping(): void {
    this.typing?.remove();
    this.typing = undefined;
  }

  /** Resets the idle countdown that degrades a stalled answer to the retry line. */
  private keepAnswerAlive(): void {
    clearTimeout(this.answerTimer);
    this.answerTimer = setTimeout(() => {
      if (this.answer?.isConnected) {
        this.clearTyping();
        // Append the retry line to any partial text, so a half answer never reads as done.
        const partial = this.answer.textContent;
        const text = partial ? `${partial} ${TRY_AGAIN_TEXT}` : TRY_AGAIN_TEXT;
        this.answer.textContent = text;
        if (this.answerModel) this.answerModel.text = text;
      }
      this.dropAnswer();
      this.save();
    }, ANSWER_IDLE_MS);
  }

  /** Stops the idle countdown and frees the answer slot. */
  private dropAnswer(): void {
    clearTimeout(this.answerTimer);
    this.clearTyping();
    this.answer = this.answerModel = this.answerMessageId = undefined;
  }

  /**
   * Persists the surface so a reload restores it. The in-flight answer is left
   * out; restore reopens it as a typing slot the buffered done frame fills.
   */
  private save(): void {
    const settled = this.answerModel
      ? this.transcript.filter((message) => message !== this.answerModel)
      : this.transcript;
    this.persist({ open: this.expanded, messages: settled, pendingId: this.answerMessageId });
  }
}

/** Builds the three-dot typing indicator, announced once to assistive tech. */
function buildTyping(): HTMLElement {
  const typing = el("span", "fr-typing");
  typing.setAttribute("role", "status");
  typing.setAttribute("aria-label", "Assistant is typing");
  typing.append(el("span", ""), el("span", ""), el("span", ""));
  return typing;
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
