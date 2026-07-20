import type { ChatSnapshot } from "./types";

const STORAGE_KEY_PREFIX = "fr_chat:";
const VERSION = 1;

// Bounds one user's stored transcript, dropping the oldest first.
const MAX_STORED_MESSAGES = 40;

/**
 * Persists the chat across a reload. sessionStorage scopes it to the tab, so
 * it survives a refresh but dies with the session, and keying it per app and
 * end user (like the stream cursor) keeps one identity's chat out of another's.
 */
export function storeChat(key: string, endUserHash: string, snapshot: ChatSnapshot): void {
  try {
    sessionStorage.setItem(
      storageKey(key, endUserHash),
      JSON.stringify({
        v: VERSION,
        open: snapshot.open,
        messages: snapshot.messages.slice(-MAX_STORED_MESSAGES),
        pendingId: snapshot.pendingId,
        composerDraft: snapshot.composerDraft,
        scrollTop: snapshot.scrollTop,
      }),
    );
  } catch {
    // Storage is blocked or full. The next reload starts fresh.
  }
}

/** Loads the stored chat, or undefined when there is none it can trust. */
export function loadChat(key: string, endUserHash: string): ChatSnapshot | undefined {
  try {
    const raw = sessionStorage.getItem(storageKey(key, endUserHash));
    if (!raw) return undefined;

    const parsed: unknown = JSON.parse(raw);
    if (!isSnapshot(parsed)) return undefined;

    // Drop the storage-only version marker, so callers get a clean snapshot.
    const { open, messages, pendingId, composerDraft, scrollTop } = parsed;

    return { open, messages, pendingId, composerDraft, scrollTop };
  } catch {
    // Storage is blocked or the entry is corrupt. Starting fresh beats throwing.
    return undefined;
  }
}

/** Clears one app and end user's stored chat, so a prior identity leaves nothing behind. */
export function clearChat(key: string, endUserHash: string): void {
  try {
    sessionStorage.removeItem(storageKey(key, endUserHash));
  } catch {
    // Storage is blocked, there is nothing to clear.
  }
}

/** Accepts only the shape this version wrote. Anything else reads as absent. */
function isSnapshot(value: unknown): value is ChatSnapshot & { v: number } {
  if (typeof value !== "object" || value === null) return false;
  const snapshot = value as Record<string, unknown>;
  return (
    snapshot.v === VERSION &&
    typeof snapshot.open === "boolean" &&
    Array.isArray(snapshot.messages) &&
    snapshot.messages.every(isMessage) &&
    (snapshot.pendingId === undefined || typeof snapshot.pendingId === "string") &&
    (snapshot.composerDraft === undefined || typeof snapshot.composerDraft === "string") &&
    (snapshot.scrollTop === undefined || typeof snapshot.scrollTop === "number")
  );
}

/** Checks one stored message: renderable text with a real timestamp. */
function isMessage(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const message = value as Record<string, unknown>;
  return (
    (message.who === "user" || message.who === "agent") &&
    typeof message.text === "string" &&
    typeof message.at === "number" &&
    (message.citations === undefined || Array.isArray(message.citations))
  );
}

/** Names the slot for one app and end user's chat, beside their stream cursor. */
function storageKey(key: string, endUserHash: string): string {
  return `${STORAGE_KEY_PREFIX}${key}:${endUserHash}`;
}
