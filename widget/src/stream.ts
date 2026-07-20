import { STREAM_PATH } from "./constants";
import { sign } from "./request";
import type { ActionPayload, Citation, Config, DoneFrame, NudgePayload, TokenFrame } from "./types";

const CURSOR_KEY_PREFIX = "fr_stream:";
const CURSOR_EARLIEST = "earliest";
const RETRY_MS = 5000;
const MAX_BACKOFF_STEPS = 6;

// Fallback cursors for pages where sessionStorage is blocked.
const memoryCursors = new Map<string, string>();

/** What the stream dispatches: one handler per server frame type. */
export interface StreamHandlers {
  nudge(payload: NudgePayload): void;
  token(messageId: string, text: string): void;
  done(messageId: string, text: string | undefined, citations: Citation[]): void;
  action(payload: ActionPayload): void;
}

/** The caller's grip on one stream: close it, or ask whether it can deliver. */
export interface StreamHandle {
  close(): void;
  /** True only while the socket is open, so a sent question's answer can arrive. */
  isOpen(): boolean;
}

/**
 * Opens the server-push channel, keyed by the end user. A fresh stream asks
 * for the whole buffer, so a nudge buffered before it opened still arrives,
 * and each seen frame narrows the cursor. The cursor rides in the url from
 * the first byte, so a server-closed reopen and the browser's own retry both
 * carry it, and it persists per app and end user across pages. A retired
 * stream stays shut.
 */
export function connectStream(
  config: Config,
  endUserHash: string,
  handlers: StreamHandlers,
): StreamHandle {
  let source: EventSource | undefined;
  let retryTimer: number | undefined;
  let retries = 0;
  let closed = false;
  let lastEventId = loadCursor(config.key, endUserHash) || CURSOR_EARLIEST;
  const seenNudges = new Set<string>();

  /** Opens one EventSource behind a fresh signed URL. */
  const open = async () => {
    // The signature binds the hash, so one signed URL cannot subscribe another
    // user. An empty signature means signing is unavailable, so the stream stays shut.
    const timestamp = new Date().toISOString();
    const signature = await sign(config.secret, timestamp, endUserHash).catch(() => "");
    if (closed || !signature) return;

    source = new EventSource(
      `${config.host}${STREAM_PATH}?key=${encodeURIComponent(config.key)}` +
        `&end_user_hash=${encodeURIComponent(endUserHash)}` +
        `&ts=${encodeURIComponent(timestamp)}&sig=${signature}` +
        `&last_event_id=${encodeURIComponent(lastEventId)}`,
    );

    /** Subscribes one frame handler, advancing the stored cursor first. */
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
          // A malformed frame never breaks the host app.
        }
      });
    };

    on("nudge", (data) => {
      const payload = JSON.parse(data) as NudgePayload;
      if (seenNudges.has(payload.id)) return;
      seenNudges.add(payload.id);
      handlers.nudge(payload);
    });
    on("token", (data) => {
      const frame = JSON.parse(data) as TokenFrame;
      handlers.token(frame.message_id, frame.text);
    });
    on("done", (data) => {
      const frame = JSON.parse(data) as DoneFrame;
      handlers.done(frame.message_id, frame.text, frame.citations ?? []);
    });
    on("action", (data) => handlers.action(JSON.parse(data) as ActionPayload));

    source.addEventListener("open", () => {
      retries = 0;
    });
    source.addEventListener("error", () => {
      // CONNECTING means the browser is already retrying on its own.
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

  return {
    close() {
      closed = true;
      clearTimeout(retryTimer);
      source?.close();
    },
    // Answer frames are live-only, so delivery needs the socket open right now.
    isOpen: () => !closed && source?.readyState === EventSource.OPEN,
  };
}

/**
 * Names the slot for one app and end user's cursor: apps on one origin
 * share storage, and an account switch in one tab must not displace the
 * previous user's position.
 */
function cursorKey(key: string, endUserHash: string): string {
  return `${CURSOR_KEY_PREFIX}${key}:${endUserHash}`;
}

/** Reads the cursor stored for this app and end user. */
function loadCursor(key: string, endUserHash: string): string {
  const storageKey = cursorKey(key, endUserHash);
  let cursor = memoryCursors.get(storageKey) ?? "";
  try {
    cursor = sessionStorage.getItem(storageKey) ?? cursor;
  } catch {
    // Storage is blocked. The in-memory copy stands in.
  }
  return cursor;
}

/** Records the cursor, so the next page's stream resumes where this one stopped. */
function storeCursor(key: string, endUserHash: string, lastEventId: string): void {
  const storageKey = cursorKey(key, endUserHash);
  memoryCursors.set(storageKey, lastEventId);
  try {
    sessionStorage.setItem(storageKey, lastEventId);
  } catch {
    // Best effort. The in-memory copy already advanced.
  }
}
