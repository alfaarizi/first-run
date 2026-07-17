import { STREAM_PATH } from "./constants";
import { sign } from "./request";
import type { ActionPayload, Citation, Config, NudgePayload } from "./types";

const RETRY_MS = 5000;
const MAX_BACKOFF_STEPS = 6;

export interface StreamHandlers {
  nudge(payload: NudgePayload): void;
  token(text: string): void;
  done(citations: Citation[]): void;
  action(payload: ActionPayload): void;
}

/**
 * Opens the server-push channel, keyed by the end user. A server-closed
 * stream reopens with backoff, carrying the last seen event id because a
 * fresh EventSource starts without one. A retired stream stays shut.
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
  let lastEventId = "";

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
        lastEventId = message.lastEventId || lastEventId;
        try {
          handle(message.data);
        } catch {
          // a malformed frame never breaks the host app
        }
      });
    };

    on("nudge", (data) => handlers.nudge(JSON.parse(data) as NudgePayload));
    on("token", (data) => handlers.token((JSON.parse(data) as { text: string }).text));
    on("done", (data) => handlers.done((JSON.parse(data) as { citations?: Citation[] }).citations ?? []));
    on("action", (data) => handlers.action(JSON.parse(data) as ActionPayload));

    source.addEventListener("open", () => {
      retries = 0;
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
