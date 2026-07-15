import { STREAM_PATH } from "./constants";
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
 * Opens the server-push channel. The browser resumes a dropped stream with
 * Last-Event-ID on its own, so only a server-closed stream reopens here
 * with backoff. EventSource cannot set headers, which puts identity on the
 * query string.
 */
export function connectStream(
  config: Config,
  session: string,
  endUserHash: string,
  handlers: StreamHandlers,
): () => void {
  let source: EventSource | undefined;
  let retryTimer: number | undefined;
  let retries = 0;
  let closed = false;

  const open = () => {
    source = new EventSource(
      `${config.host}${STREAM_PATH}?key=${encodeURIComponent(config.key)}` +
        `&session_id=${session}&end_user_hash=${encodeURIComponent(endUserHash)}`,
    );

    const on = (name: string, handle: (data: string) => void) => {
      source?.addEventListener(name, (e) => {
        try {
          handle((e as MessageEvent<string>).data);
        } catch {
          // a malformed frame never breaks the host app
        }
      });
    };

    on("open", () => {
      retries = 0;
    });
    on("nudge", (data) => handlers.nudge(JSON.parse(data) as NudgePayload));
    on("token", (data) => handlers.token((JSON.parse(data) as { text: string }).text));
    on("done", (data) => handlers.done((JSON.parse(data) as { citations?: Citation[] }).citations ?? []));
    on("action", (data) => handlers.action(JSON.parse(data) as ActionPayload));

    source.addEventListener("error", () => {
      // CONNECTING means the browser is already retrying on its own
      if (closed || source?.readyState !== EventSource.CLOSED) return;
      retryTimer = setTimeout(open, RETRY_MS * Math.min(++retries, MAX_BACKOFF_STEPS));
    });
  };
  
  open();

  return () => {
    closed = true;
    clearTimeout(retryTimer);
    source?.close();
  };
}
