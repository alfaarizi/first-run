import { filterProperties } from "./allowlist";
import { startAutocapture } from "./autocapture";
import { CONFIRMATIONS_PATH, MESSAGES_PATH } from "./constants";
import { showConfirmation } from "./confirm";
import { NudgeUi } from "./nudge";
import { post } from "./request";
import { RequestQueue } from "./request-queue";
import { sessionId } from "./sessionid";
import { connectStream } from "./stream";
import type { Config, Properties } from "./types";
import { uuidv7 } from "./uuidv7";

// the ingest grammar for custom names, which also excludes the reserved fr. prefix
const EVENT_NAME = /^[a-z][a-z0-9_]{0,63}$/;

const PENDING_LIMIT = 100;

/** An event held until identify supplies the customer's hash. */
type PendingEvent = [event: string, properties: Properties | undefined, timestamp: string];

declare global {
  interface Window {
    fr?: {
      identify(endUserHash: string): void;
      track(event: string, properties?: Properties): void;
    };
  }
}

/** Wraps fn so no widget exception ever reaches the host app. */
function safe<A extends unknown[]>(fn: (...args: A) => void): (...args: A) => void {
  return (...args) => {
    try {
      fn(...args);
    } catch {
      // fail silent
    }
  };
}

/** Reads config while document.currentScript still points at the snippet. */
function readConfig(): Config | undefined {
  const script = document.currentScript;
  if (!(script instanceof HTMLScriptElement)) return undefined;
  const { key, host, secret, allowlist } = script.dataset;
  if (!key || !host || !secret) return undefined;
  return {
    key,
    host,
    secret,
    allowlist: new Set((allowlist ?? "").split(",").filter(Boolean)),
  };
}

function start(config: Config): void {
  const queue = new RequestQueue(config);
  let endUserHash = "";
  let pendingEvents: PendingEvent[] = [];

  const send = (event: string, properties: Properties | undefined, timestamp: string) => {
    queue.enqueue({
      id: uuidv7(),
      event,
      end_user_hash: endUserHash,
      session_id: sessionId(),
      timestamp,
      ...(properties && { properties }),
    });
  };

  const capture = (event: string, properties?: Properties) => {
    const timestamp = new Date().toISOString();
    if (endUserHash) send(event, properties, timestamp);
    else if (pendingEvents.length < PENDING_LIMIT) pendingEvents.push([event, properties, timestamp]);
  };

  const ui = new NudgeUi({
    onDismiss: safe((nudgeId) => capture("fr.nudge_dismissed", { nudge_id: nudgeId })),
    onEngage: safe((nudgeId) => capture("fr.nudge_engaged", { nudge_id: nudgeId })),
    onSend: (text) =>
      post(
        config,
        MESSAGES_PATH,
        JSON.stringify({ session_id: sessionId(), end_user_hash: endUserHash, text }),
      ).then((result) => result.ok),
  });

  const identify = safe((hash: string) => {
    if (!hash || endUserHash) return;
    endUserHash = hash;

    for (const args of pendingEvents) send(...args);
    pendingEvents = [];
    connectStream(config, sessionId(), hash, {
      nudge: safe((payload) => ui.showNudge(payload)),
      token: safe((text) => ui.appendToken(text)),
      done: safe((citations) => ui.finishAnswer(citations)),
      action: safe((payload) =>
        showConfirmation(ui.container, payload, {
          onConfirm: async (executionId) => {
            const result = await post(
              config,
              CONFIRMATIONS_PATH,
              JSON.stringify({
                execution_id: executionId,
                session_id: sessionId(),
                end_user_hash: endUserHash,
              }),
            );
            return result.ok;
          },
          onCancel: safe((executionId) =>
            capture("fr.action_cancelled", { execution_id: executionId }),
          ),
        }),
      ),
    });
  });

  const track = safe((event: string, properties?: Properties) => {
    // one rejected name never poisons a batch the gateway validates as a whole
    if (typeof event !== "string" || !EVENT_NAME.test(event)) return;
    const kept = filterProperties(properties ?? {}, config.allowlist);
    capture(event, Object.keys(kept).length > 0 ? kept : undefined);
  });

  startAutocapture(safe(capture));
  window.fr = { identify, track };
}

safe(() => {
  if (window.fr) return;
  const config = readConfig();
  if (config) start(config);
})();
