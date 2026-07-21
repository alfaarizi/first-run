import { TRY_AGAIN_TEXT } from "./constants";
import { el } from "./dom";
import type { ActionPayload } from "./types";

/** What the card reports: the explicit confirm click, or the cancel. */
export interface ConfirmCallbacks {
  onConfirm(executionId: string): Promise<boolean>;
  onCancel(executionId: string): void;
}

/**
 * Renders the confirmation card with the registry's copy. Nothing executes
 * until the user clicks Confirm, and no auto-confirm path exists.
 */
export function showConfirmation(
  container: HTMLElement,
  action: ActionPayload,
  callbacks: ConfirmCallbacks,
): void {
  // One pending confirmation at a time, so a hostile stream cannot flood cards.
  container.querySelector(".fr-confirm")?.remove();

  const card = el("div", "fr-card fr-confirm");
  const text = el("p", "fr-text", action.copy);
  const cancelButton = el("button", "fr-btn", "Cancel");
  const confirmButton = el("button", "fr-btn fr-btn-primary", "Confirm");

  cancelButton.onclick = () => {
    card.remove();
    callbacks.onCancel(action.execution_id);
  };

  confirmButton.onclick = async () => {
    confirmButton.disabled = cancelButton.disabled = true;
    if (await callbacks.onConfirm(action.execution_id)) {
      card.remove();
    } else {
      confirmButton.disabled = cancelButton.disabled = false;
      text.textContent = `${action.copy}\n\n${TRY_AGAIN_TEXT}`;
    }
  };

  const actions = el("div", "fr-actions");
  actions.append(cancelButton, confirmButton);
  card.append(text, actions);
  container.append(card);
}
