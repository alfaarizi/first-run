import { STREAM_PATH } from "./constants";
import { sign } from "./request";
import type { ActionPayload, Citation, Config, NudgePayload } from "./types";

const RETRY_MS = 5000;
const MAX_BACKOFF_STEPS = 6;

const CURSOR_KEY = "fr_stream";

// the reserved cursor asking the server to replay its whole buffer
const EARLIEST = "earliest";

let memoryCursor = "";

/** Reads the stored cursor, honored only for the same end user. */
function loadCursor(endUserHash: string): string {
  let raw = memoryCursor;
  try {
    raw = sessionStorage.getItem(CURSOR_KEY) ?? raw;
  } catch {
    // storage is blocked, use the in-memory copy
  }
  const splitAt = raw.indexOf(":");
  return splitAt > 0 && raw.slice(splitAt + 1) === endUserHash ? raw.slice(0, splitAt) : "";
}

/** Records the cursor, so the next page's stream resumes where this one stopped. */
function storeCursor(endUserHash: string, lastEventId: string): void {
  memoryCursor = `${lastEventId}:${endUserHash}`;
  try {
    sessionStorage.setItem(CURSOR_KEY, memoryCursor);
  } catch {
    // best effort
  }
}

export interface StreamHandlers {
  nudge(payload: NudgePayload): void;
  token(text: string): void;
  done(citations: Citation[]): void;
  action(payload: ActionPayload): void;
}

/**
 * Opens the server-push channel, keyed by the end user. A server-closed
 * stream reopens with backoff, carrying the last seen event id because a
 * fresh EventSource starts without one, and a reopen that never saw a frame
 * asks for the whole buffer. The cursor persists per end user, so the next
 * page's stream replays what a navigation gap missed. A retired stream
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
  let everOpened = false;
  let lastEventId = loadCursor(endUserHash);
  const seenNudges = new Set<string>();

  const open = async () => {
    // the signature binds the hash, so one signed url cannot subscribe another
    // user, and an empty sig means signing is unavailable so the stream stays shut
    const timestamp = new Date().toISOString();
    const signature = await sign(config.secret, timestamp, endUserHash).catch(() => "");
    if (closed || !signature) return;

    const cursor = lastEventId || (everOpened ? EARLIEST : "");
    source = new EventSource(
      `${config.host}${STREAM_PATH}?key=${encodeURIComponent(config.key)}` +
        `&end_user_hash=${encodeURIComponent(endUserHash)}` +
        `&ts=${encodeURIComponent(timestamp)}&sig=${signature}` +
        (cursor ? `&last_event_id=${encodeURIComponent(cursor)}` : ""),
    );

    const on = (name: string, handle: (data: string) => void) => {
      source?.addEventListener(name, (e) => {
        const message = e as MessageEvent<string>;
        if (message.lastEventId && message.lastEventId !== lastEventId) {
          lastEventId = message.lastEventId;
          storeCursor(endUserHash, lastEventId);
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
      // a replayed frame can race its live copy, so the id gates a second showing
      if (seenNudges.has(payload.id)) return;
      seenNudges.add(payload.id);
      handlers.nudge(payload);
    });
    on("token", (data) => handlers.token((JSON.parse(data) as { text: string }).text));
    on("done", (data) => handlers.done((JSON.parse(data) as { citations?: Citation[] }).citations ?? []));
    on("action", (data) => handlers.action(JSON.parse(data) as ActionPayload));

    source.addEventListener("open", () => {
      retries = 0;
      everOpened = true;
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
