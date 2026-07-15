import { uuidv7 } from "./uuidv7";

const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const STORAGE_KEY = "fr_session";
const IDLE_MS = 30 * 60 * 1000;

let memoryId = "";
let memoryTouchedAt = 0;

/**
 * Returns the current session id, rotating it after 30 idle minutes. When
 * storage is blocked the id lives in memory only, which shortens sessions
 * but never throws into the host app.
 */
export function sessionId(now = Date.now()): string {
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
    // storage is blocked, use the in-memory copy
  }

  // a corrupt store rotates instead of poisoning every batch
  if (!UUID.test(id) || !Number.isFinite(touchedAt) || now - touchedAt > IDLE_MS) id = uuidv7();
  memoryId = id;
  memoryTouchedAt = now;

  try {
    sessionStorage.setItem(STORAGE_KEY, `${now}:${id}`);
  } catch {
    // best effort
  }
  return id;
}
