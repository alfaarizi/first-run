import { isUuidv7, uuidv7 } from "./uuidv7";

const STORAGE_KEY = "fr_session";
const IDLE_MS = 30 * 60 * 1000;

let memoryId = "";
let memoryTouchedAt = 0;

/**
 * Returns the current session id, rotating it after 30 idle minutes. When
 * storage is blocked the id lives in memory, so sessions shorten but never
 * throw into the host app.
 */
export function currentSessionId(now = Date.now()): string {
  let id = memoryId;
  let touchedAt = memoryTouchedAt;
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    const splitAt = raw ? raw.indexOf(":") : -1;
    if (raw && splitAt > 0) {
      touchedAt = Number(raw.slice(0, splitAt));
      id = raw.slice(splitAt + 1);
    }
  } catch {
    // Storage is blocked. The in-memory copy stands in.
  }

  // A corrupt store rotates instead of poisoning every batch.
  if (!isUuidv7(id) || !Number.isFinite(touchedAt) || now - touchedAt > IDLE_MS) id = uuidv7();
  storeSession(now, id);
  return id;
}

/** Records the id as the active session, in memory and in storage. */
function storeSession(now: number, id: string): void {
  memoryId = id;
  memoryTouchedAt = now;
  try {
    sessionStorage.setItem(STORAGE_KEY, `${now}:${id}`);
  } catch {
    // Best effort. The in-memory copy already advanced.
  }
}

/** Starts a fresh session, used when the identified end user changes. */
export function rotateSession(now = Date.now()): void {
  storeSession(now, uuidv7());
}
