import { STREAM_PATH } from "./constants";
import { sign } from "./request";
import type { ActionPayload, Citation, Config, NudgePayload } from "./types";

const CURSOR_KEY_PREFIX = "fr_stream:";
const CURSOR_EARLIEST = "earliest";
const RETRY_MS = 5000;
const MAX_BACKOFF_STEPS = 6;

const memoryCursors = new Map<string, string>();

export interface StreamHandlers {
  nudge(payload: NudgePayload): void;
  token(text: string): void;
  done(citations: Citation[]): void;
  action(payload: ActionPayload): void;
}

/**
 * Opens the server-push channel, keyed by the end user. A server-closed
 * stream reopens with backoff, carrying the last seen event id because a
 * fresh EventSource starts without one. A stream that opens before any
 * frame arrives claims the whole buffer, and the claim persists with the
 * cursor per app and end user, so the next page's stream replays what a
 * navigation gap missed even when no nudge ever arrived. A retired stream
 * stays shut.
 */
export function connectStream(
  config: Config,
  endUserHash: string,
  handlers: StreamHandlers,
): () => void {
  let source: EventSource | undefined;
  let retryTimer: number | undefined;
  let retries = 0;
  let closed = false;
  let lastEventId = loadCursor(config.key, endUserHash);
  const seenNudges = new Set<string>();

  const open = async () => {
    // the signature binds the hash, so one signed url cannot subscribe another
    // user, and an empty sig means signing is unavailable so the stream stays shut
    const timestamp = new Date().toISOString();
    const signature = await sign(config.secret, timestamp, endUserHash).catch(() => "");
    if (closed || !signature) return;

    source = new EventSource(
      `${config.host}${STREAM_PATH}?key=${encodeURIComponent(config.key)}` +
        `&end_user_hash=${encodeURIComponent(endUserHash)}` +
        `&ts=${encodeURIComponent(timestamp)}&sig=${signature}` +
        (lastEventId ? `&last_event_id=${encodeURIComponent(lastEventId)}` : ""),
    );

    const on = (name: string, handle: (data: string) => void) => {
      source?.addEventListener(name, (e) => {
        const message = e as MessageEvent<string>;
        if (message.lastEventId && message.lastEventId !== lastEventId) {
          lastEventId = message.lastEventId;
          storeCursor(config.key, endUserHash, lastEventId);
        }
        try {
          handle(message.data);
        } catch {
          // a malformed frame never breaks the host app
        }
      });
    };

    on("nudge", (data) => {
      const payload = JSON.parse(data) as NudgePayload;
      if (seenNudges.has(payload.id)) return;
      seenNudges.add(payload.id);
      handlers.nudge(payload);
    });
    on("token", (data) => handlers.token((JSON.parse(data) as { text: string }).text));
    on("done", (data) => handlers.done((JSON.parse(data) as { citations?: Citation[] }).citations ?? []));
    on("action", (data) => handlers.action(JSON.parse(data) as ActionPayload));

    source.addEventListener("open", () => {
      retries = 0;
      // an opened stream with no cursor claims the whole buffer, and persisting the claim
      // means even the first nudge, buffered while the next page loads, still replays
      if (!lastEventId) {
        lastEventId = CURSOR_EARLIEST;
        storeCursor(config.key, endUserHash, lastEventId);
      }
    });
    source.addEventListener("error", () => {
      // CONNECTING means the browser is already retrying on its own
      if (closed || source?.readyState !== EventSource.CLOSED) return;
      retryTimer = setTimeout(open, RETRY_MS * Math.min(++retries, MAX_BACKOFF_STEPS));
    });
    source.addEventListener("retired", () => {
      closed = true;
      clearTimeout(retryTimer);
      source?.close();
    });
  };

  void open();

  return () => {
    closed = true;
    clearTimeout(retryTimer);
    source?.close();
  };
}

/** Reads the app's stored cursor, honored only for the same end user. */
function loadCursor(key: string, endUserHash: string): string {
  const storageKey = CURSOR_KEY_PREFIX + key;
  let raw = memoryCursors.get(storageKey) ?? "";
  try {
    raw = sessionStorage.getItem(storageKey) ?? raw;
  } catch {
    // storage is blocked, use the in-memory copy
  }
  const splitAt = raw.indexOf(":");
  return splitAt > 0 && raw.slice(splitAt + 1) === endUserHash ? raw.slice(0, splitAt) : "";
}

/**
 * Records the cursor, so the next page's stream resumes where this one
 * stopped. The key carries the app, because two apps on one origin share
 * storage while their frame ids never interchange.
 */
function storeCursor(key: string, endUserHash: string, lastEventId: string): void {
  const storageKey = CURSOR_KEY_PREFIX + key;
  const value = `${lastEventId}:${endUserHash}`;
  memoryCursors.set(storageKey, value);
  try {
    sessionStorage.setItem(storageKey, value);
  } catch {
    // best effort
  }
}
