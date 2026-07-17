import { el } from "./dom";
import { COMPOSER_MAX_HEIGHT_PX } from "./nudge.css";

/** The message input row, an autogrowing textarea beside a send button. */
export interface Composer {
  root: HTMLElement;
  /** The trimmed draft. */
  text(): string;
  clear(): void;
  focus(): void;
}

/** Builds the composer. Enter and the send button submit, Shift+Enter breaks the line. */
export function createComposer(onSubmit: () => void): Composer {
  const input = el("textarea", "fr-input");
  input.rows = 1;
  input.placeholder = "Ask about this product...";
  input.setAttribute("aria-label", "Ask about this product");
  input.oninput = resize;
  input.onkeydown = (e) => {
    // an Enter that commits IME composition keeps composing, and Safari fires
    // it after compositionend with isComposing already false, so 229 gates it
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
      e.preventDefault();
      onSubmit();
    }
  };

  const send = el("button", "fr-send");
  send.append(sendIcon());
  send.setAttribute("aria-label", "Send");
  send.onclick = onSubmit;

  const root = el("div", "fr-composer");
  root.append(input, send);

  // grows the composer with its content and hands overflow to a scrollbar at the cap
  function resize(): void {
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, COMPOSER_MAX_HEIGHT_PX)}px`;
    input.style.overflowY = input.scrollHeight > COMPOSER_MAX_HEIGHT_PX ? "auto" : "hidden";
  }

  return {
    root,
    text: () => input.value.trim(),
    clear() {
      input.value = "";
      resize();
    },
    focus() {
      // scroll-on-focus would drag the shell's content while it is mid-morph
      input.focus({ preventScroll: true });
    },
  };
}

/** Builds the send arrow with DOM calls alone, so no markup parser or injection sink exists. */
function sendIcon(): SVGSVGElement {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.setAttribute("aria-hidden", "true");
  const arrow = document.createElementNS(NS, "path");
  arrow.setAttribute("d", "M8 13V3M3.5 7.5 8 3l4.5 4.5");
  svg.append(arrow);
  return svg;
}
