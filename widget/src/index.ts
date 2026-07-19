import { filterProperties } from "./allowlist";
import { startAutocapture } from "./autocapture";
import { CONFIRMATIONS_PATH, MESSAGES_PATH } from "./constants";
import { showConfirmation } from "./confirm";
import { NudgeUi } from "./nudge";
import { post } from "./request";
import { RequestQueue } from "./request-queue";
import { rotateSession, sessionId } from "./sessionid";
import { connectStream } from "./stream";
import type { Config, Properties } from "./types";
import { uuidv7 } from "./uuidv7";

// The ingest grammar for custom names. It also excludes the reserved fr. prefix.
const EVENT_NAME = /^[a-z][a-z0-9_]{0,63}$/;
const MAX_END_USER_HASH_LENGTH = 128;
const MAX_PENDING = 100;

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
      // Fail silent.
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

/** Wires the whole widget: capture queue, surface, push stream, and window.fr. */
function start(config: Config): void {
  const queue = new RequestQueue(config);
  let endUserHash = "";
  let pendingEvents: PendingEvent[] = [];

  /** Queues one event under the current identity and session. */
  const enqueueEvent = (
    event: string,
    properties: Properties | undefined,
    timestamp: string,
    ref?: string,
  ) => {
    queue.enqueue({
      id: uuidv7(),
      event,
      end_user_hash: endUserHash,
      session_id: sessionId(),
      timestamp,
      ...(ref && { ref }),
      ...(properties && Object.keys(properties).length > 0 && { properties }),
    });
  };

  /**
   * Captures one event, held until identify supplies the hash. Every
   * property passes the allowlist client-side, autocapture included.
   */
  const capture = (event: string, properties?: Properties) => {
    const timestamp = new Date().toISOString();
    const allowedProperties = properties && filterProperties(properties, config.allowlist);
    if (endUserHash) enqueueEvent(event, allowedProperties, timestamp);
    else if (pendingEvents.length < MAX_PENDING) {
      pendingEvents.push([event, allowedProperties, timestamp]);
    }
  };

  /** Queues an outcome event. It answers a rendered frame, so identity is already set. */
  const enqueueInterventionEvent = (event: string, ref: string) =>
    enqueueEvent(event, undefined, new Date().toISOString(), ref);

  const ui = new NudgeUi({
    onDismiss: safe((nudgeId) => enqueueInterventionEvent("fr.nudge_dismissed", nudgeId)),
    onEngage: safe((nudgeId) => enqueueInterventionEvent("fr.nudge_engaged", nudgeId)),
    onSend: (id, text, ref) =>
      post(
        config,
        MESSAGES_PATH,
        JSON.stringify({
          id,
          session_id: sessionId(),
          end_user_hash: endUserHash,
          text,
          ...(ref && { ref }),
        }),
      ).then((result) => result.ok),
  });

  let disconnect: (() => void) | undefined;

  /** Adopts the end user's hash, releasing held events and (re)opening their stream. */
  const identify = safe((hash: string) => {
    // One bad hash would poison every later batch, so the contract's cap gates here.
    if (typeof hash !== "string" || !hash.trim() || hash.length > MAX_END_USER_HASH_LENGTH) return;
    if (hash === endUserHash) return;

    // An account switch gets a fresh session, and the old surface never leaks.
    if (endUserHash) {
      rotateSession();
      ui.reset();
    }
    endUserHash = hash;

    for (const args of pendingEvents) enqueueEvent(...args);
    pendingEvents = [];

    disconnect?.();
    disconnect = connectStream(config, hash, {
      nudge: safe((payload) => ui.showNudge(payload)),
      token: safe((messageId, text) => ui.appendToken(messageId, text)),
      done: safe((messageId, citations) => ui.finishAnswer(messageId, citations)),
      action: safe((payload) => {
        ui.notify();
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
            enqueueInterventionEvent("fr.action_cancelled", executionId),
          ),
        });
      }),
    });
  });

  /** Captures one founder-named event, dropping names outside the grammar. */
  const track = safe((event: string, properties?: Properties) => {
    // One rejected name never poisons a batch the gateway validates as a whole.
    if (typeof event !== "string" || !EVENT_NAME.test(event)) return;
    capture(event, properties);
  });

  startAutocapture(safe(capture));
  window.fr = { identify, track };
}

// Boots once per page; a second snippet is a no-op.
safe(() => {
  if (window.fr) return;
  const config = readConfig();
  if (config) start(config);
})();
