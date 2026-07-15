import { TRY_AGAIN_TEXT } from "./constants";
import { el } from "./dom";
import type { Citation, NudgePayload } from "./types";

export interface NudgeCallbacks {
  onDismiss(nudgeId: string): void;
  onEngage(nudgeId: string): void;
  onSend(text: string): Promise<boolean>;
}

const CSS = `
:host {
  all: initial;

  --background: #fff;
  --accent-background: #3b3bd1;
  --user-message-background: #ecebff;
  --agent-message-background: #f4f4f8;
  --foreground: #1a1a2e;
  --accent-foreground: #fff;
  --muted-foreground: #8a8a9a;
  --link-foreground: #3b3bd1;
  --border: 1px solid #e2e2ea;
  --box-shadow: 0 8px 24px rgba(20, 20, 40, 0.16);
  --outline: 1px auto var(--accent-background);
  --interactive-filter: brightness(95%);
  --font-family: system-ui, "Helvetica Neue", Arial, sans-serif;
  --font-size: 14px;
  --z-index: 2147483647;
}
@media (prefers-color-scheme: dark) {
  :host {
    color-scheme: only dark;

    --background: #23232f;
    --user-message-background: #35355c;
    --agent-message-background: #2e2e3c;
    --foreground: #e9e9f2;
    --muted-foreground: #a2a2b5;
    --link-foreground: #a5a5ff;
    --border: 1px solid #3a3a4a;
    --box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    --interactive-filter: brightness(130%);
  }
}
.fr-root {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: var(--z-index);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  font-family: var(--font-family);
  font-size: var(--font-size);
  line-height: 1.45;
  color: var(--foreground);
}
.fr-card {
  box-sizing: border-box;
  width: 320px;
  background: var(--background);
  border: var(--border);
  border-radius: 12px;
  box-shadow: var(--box-shadow);
  padding: 12px 14px;
}
.fr-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.fr-text {
  flex: 1;
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}
.fr-btn {
  font: inherit;
  color: inherit;
  border: var(--border);
  border-radius: 8px;
  background: var(--background);
  padding: 5px 10px;
  cursor: pointer;
}
.fr-btn:hover:not(:disabled) {
  filter: var(--interactive-filter);
}
.fr-btn:disabled {
  opacity: 0.5;
  cursor: default;
}
.fr-btn:focus-visible,
.fr-close:focus-visible,
.fr-input:focus-visible {
  outline: var(--outline);
}
.fr-btn-primary {
  background: var(--accent-background);
  border-color: var(--accent-background);
  color: var(--accent-foreground);
}
.fr-close {
  border: 0;
  background: none;
  padding: 0 2px;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  color: var(--muted-foreground);
}
.fr-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.fr-messages {
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}
.fr-message {
  border-radius: 8px;
  padding: 6px 9px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
}
.fr-message-user {
  align-self: flex-end;
  background: var(--user-message-background);
}
.fr-message-agent {
  align-self: flex-start;
  background: var(--agent-message-background);
}
.fr-citations {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
}
.fr-citations a {
  color: var(--link-foreground);
}
.fr-input-row {
  display: flex;
  gap: 6px;
}
.fr-input {
  flex: 1;
  font: inherit;
  color: inherit;
  background: var(--background);
  border: var(--border);
  border-radius: 8px;
  padding: 6px 9px;
}
.fr-input::placeholder {
  color: var(--muted-foreground);
}
`;

/**
 * The widget surface, a dismissible nudge that expands into chat.
 * Everything renders inside a closed shadow root, and dynamic strings only
 * land in textContent, so server text stays text.
 */
export class NudgeUi {
  private readonly callbacks: NudgeCallbacks;
  private readonly root: HTMLElement;
  private nudgeCard: HTMLElement | undefined;
  private chatCard: HTMLElement | undefined;
  private messages: HTMLElement | undefined;
  private answer: HTMLElement | undefined;
  private nudgeId = "";

  constructor(callbacks: NudgeCallbacks) {
    this.callbacks = callbacks;

    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = CSS;
    this.root = el("div", "fr-root");
    shadow.append(style, this.root);

    if (document.body) document.body.append(host);
    else addEventListener("DOMContentLoaded", () => document.body.append(host));
  }

  /** Where the confirmation card mounts, above the nudge. */
  get container(): HTMLElement {
    return this.root;
  }

  showNudge(nudge: NudgePayload): void {
    this.nudgeCard?.remove();
    this.nudgeId = nudge.id;

    const card = el("div", "fr-card");
    const row = el("div", "fr-row");
    row.append(
      el("p", "fr-text", nudge.text),
      this.createCloseButton("Dismiss", () => {
        card.remove();
        this.callbacks.onDismiss(this.nudgeId);
      }),
    );

    const ask = el("button", "fr-btn fr-btn-primary", "Ask a question");
    ask.onclick = () => {
      card.remove();
      this.openChat();
      this.callbacks.onEngage(this.nudgeId);
    };

    const actions = el("div", "fr-actions");
    actions.append(ask);

    card.append(row, actions);
    this.nudgeCard = card;
    this.root.append(card);
  }

  openChat(): void {
    if (this.chatCard) return;

    const card = el("div", "fr-card");
    const row = el("div", "fr-row");
    this.messages = el("div", "fr-messages");
    row.append(
      this.messages,
      this.createCloseButton("Close chat", () => {
        card.remove();
        this.chatCard = this.messages = this.answer = undefined;
      }),
    );

    const input = el("input", "fr-input");
    input.placeholder = "Ask about this product...";

    const send = el("button", "fr-btn fr-btn-primary", "Send");
    const submit = () => {
      const text = input.value.trim();
      if (!text || this.answer) return;

      input.value = "";
      this.messages?.append(el("div", "fr-message fr-message-user", text));

      const answer = el("div", "fr-message fr-message-agent");
      this.answer = answer;
      this.messages?.append(answer);

      void this.callbacks.onSend(text).then((delivered) => {
        // a failed send frees the slot, so the user can try again
        if (!delivered && this.answer === answer) {
          answer.textContent = TRY_AGAIN_TEXT;
          this.answer = undefined;
        }
      });
    };
    send.onclick = submit;
    input.onkeydown = (e) => {
      if (e.key === "Enter") submit();
    };

    const inputRow = el("div", "fr-input-row");
    inputRow.append(input, send);
    card.append(row, inputRow);

    this.chatCard = card;
    this.root.append(card);
    input.focus();
  }

  appendToken(text: string): void {
    if (!this.answer) return;
    this.answer.append(text);
    this.messages?.scrollTo(0, this.messages.scrollHeight);
  }

  finishAnswer(citations: Citation[]): void {
    if (this.answer) {
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
    }
    this.answer = undefined;
  }

  private createCloseButton(label: string, onClick: () => void): HTMLElement {
    // the multiplication sign, the conventional close glyph
    const button = el("button", "fr-close", "\u00d7");
    button.setAttribute("aria-label", label);
    button.onclick = onClick;
    return button;
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
