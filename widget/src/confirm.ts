import { el } from "./dom";
import type { ActionPayload } from "./types";

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
  // one pending confirmation at a time, so a hostile stream cannot flood cards
  container.querySelector(".fr-confirm")?.remove();

  const card = el("div", "fr-card fr-confirm");
  const copy = el("p", "fr-text", action.copy);
  const cancel = el("button", "fr-btn", "Cancel");
  const confirm = el("button", "fr-btn fr-btn-primary", "Confirm");

  cancel.onclick = () => {
    card.remove();
    callbacks.onCancel(action.execution_id);
  };

  confirm.onclick = async () => {
    confirm.disabled = cancel.disabled = true;
    if (await callbacks.onConfirm(action.execution_id)) {
      card.remove();
    } else {
      confirm.disabled = cancel.disabled = false;
      copy.textContent = `${action.copy}\n\nThat did not go through. Try again.`;
    }
  };

  const actions = el("div", "fr-actions");
  actions.append(cancel, confirm);
  card.append(copy, actions);
  container.append(card);
}
