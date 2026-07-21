import { el } from "./dom";
import { COMPOSER_LINE_HEIGHT_PX, COMPOSER_MAX_HEIGHT_PX } from "./nudge.css";

// The messages contract's cap. Enforced here so an oversized paste stays
// editable instead of bouncing off the server after send.
const MESSAGE_MAX_CHARS = 2000;

/** The message input row, an autogrowing textarea beside a send button. */
export interface Composer {
  root: HTMLElement;
  /** The trimmed draft, ready to send. */
  text(): string;
  /** The raw draft, kept verbatim so a reload restores it exactly. */
  draft(): string;
  /** Seeds the draft, growing the input to fit, used when a reload restores it. */
  setDraft(value: string): void;
  clearDraft(): void;
  /** Opens or blocks the input and its send button. */
  setEnabled(enabled: boolean): void;
  focus(): void;
}

/** Builds the composer. Enter and the send button submit, Shift+Enter breaks the line. */
export function createComposer(onSubmit: () => void): Composer {
  const input = el("textarea", "fr-input");
  input.rows = 1;
  input.maxLength = MESSAGE_MAX_CHARS;
  input.placeholder = "Ask about this product...";
  input.setAttribute("aria-label", "Ask about this product");
  input.oninput = syncToDraft;
  input.onkeydown = (e) => {
    // An Enter that commits IME composition keeps composing. Safari fires it
    // after compositionend with isComposing already false, so 229 gates it.
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
      e.preventDefault();
      onSubmit();
    }
    if ((e.key === "ArrowUp" || e.key === "ArrowDown") && !e.altKey && !e.ctrlKey && !e.metaKey) {
      clampScroll();
    }
  };

  const send = el("button", "fr-send");
  send.append(buildSendIcon());
  send.setAttribute("aria-label", "Send");
  send.onclick = onSubmit;

  const root = el("div", "fr-composer");
  root.append(input, send);

  /** Caps caret-driven scroll jumps at one line, so arrow keys pan smoothly. */
  function clampScroll(): void {
    const before = input.scrollTop;
    requestAnimationFrame(() => {
      const jump = input.scrollTop - before;
      if (Math.abs(jump) > COMPOSER_LINE_HEIGHT_PX) {
        input.scrollTop = before + Math.sign(jump) * COMPOSER_LINE_HEIGHT_PX;
      }
    });
  }

  /**
   * Grows the composer with its content, handing overflow to a scrollbar at
   * the cap, and arms the send button only for a draft submit would accept.
   */
  function syncToDraft(): void {
    input.style.height = "auto";
    // Read the content height once. Measuring it again after setting height reflows twice.
    const contentHeight = input.scrollHeight;
    input.style.height = `${Math.min(contentHeight, COMPOSER_MAX_HEIGHT_PX)}px`;
    input.style.overflowY = contentHeight > COMPOSER_MAX_HEIGHT_PX ? "auto" : "hidden";
    send.classList.toggle("fr-send-ready", /\S/.test(input.value));
  }

  /** Sets the draft and grows the input to fit it. */
  function setDraft(value: string): void {
    input.value = value;
    syncToDraft();
  }

  return {
    root,
    text: () => input.value.trim(),
    draft: () => input.value,
    setDraft,
    clearDraft: () => setDraft(""),
    setEnabled(enabled) {
      input.disabled = send.disabled = !enabled;
    },
    focus() {
      // Scroll-on-focus would drag the shell's content while it is mid-morph.
      input.focus({ preventScroll: true });
    },
  };
}

/** Builds the send arrow with DOM calls alone, so no markup parser or injection sink exists. */
function buildSendIcon(): SVGSVGElement {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.setAttribute("aria-hidden", "true");
  const arrow = document.createElementNS(NS, "path");
  arrow.setAttribute("d", "M8 13V3M3.5 7.5 8 3l4.5 4.5");
  svg.append(arrow);
  return svg;
}
