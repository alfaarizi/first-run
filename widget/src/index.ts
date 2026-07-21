import { filterProperties } from "./allowlist";
import { startAutocapture } from "./autocapture";
import { clearChat, loadChat, storeChat } from "./chat-store";
import { CONFIRMATIONS_PATH, MESSAGES_PATH } from "./constants";
import { showConfirmation } from "./confirm";
import { NudgeUi } from "./nudge";
import { post } from "./request";
import { RequestQueue } from "./request-queue";
import { rotateSession, currentSessionId } from "./session-id";
import { connectStream } from "./stream";
import type { StreamHandle } from "./stream";
import type { Config, Properties } from "./types";
import { uuidv7 } from "./uuidv7";

// The ingest grammar for custom names. It also excludes the reserved fr. prefix.
const EVENT_NAME = /^[a-z][a-z0-9_]{0,63}$/;
const MAX_END_USER_HASH_LENGTH = 128;
const MAX_PENDING_EVENTS = 100;

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
function failSilent<A extends unknown[]>(fn: (...args: A) => void): (...args: A) => void {
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
      session_id: currentSessionId(),
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
    else if (pendingEvents.length < MAX_PENDING_EVENTS) {
      pendingEvents.push([event, allowedProperties, timestamp]);
    }
  };

  /** Queues an outcome event. It answers a rendered frame, so identity is already set. */
  const enqueueInterventionEvent = (event: string, ref: string) =>
    enqueueEvent(event, undefined, new Date().toISOString(), ref);

  let stream: StreamHandle | undefined;

  const ui = new NudgeUi(
    {
      onDismiss: failSilent((nudgeId) => enqueueInterventionEvent("fr.nudge_dismissed", nudgeId)),
      onEngage: failSilent((nudgeId) => enqueueInterventionEvent("fr.nudge_engaged", nudgeId)),
      onSend: (id, text, ref) =>
        // Answer frames are live-only, so a send without an open stream spends
        // an answer nobody receives. Fail it now instead of after an idle wait.
        !stream?.isOpen()
          ? Promise.resolve(false)
          : post(
              config,
              MESSAGES_PATH,
              JSON.stringify({
                id,
                session_id: currentSessionId(),
                end_user_hash: endUserHash,
                text,
                ...(ref && { ref }),
              }),
              // A retryable failure may have reached the server, whose accepted
              // answer then rides the stream. Only a definite rejection should
              // settle the question, never discards a live answer.
            ).then((result) => result.ok || result.retryable),
    },
    // Persisted under the current identity, so a reload restores this user's
    // conversation and never another's. A no-op until identify sets the hash.
    (snapshot) => {
      if (endUserHash) storeChat(config.key, endUserHash, snapshot);
    },
  );

  /** Adopts the end user's hash, releasing held events and (re)opening their stream. */
  const identify = failSilent((hash: string) => {
    // One bad hash would poison every later batch, so the contract's cap gates here.
    if (typeof hash !== "string" || !hash.trim() || hash.length > MAX_END_USER_HASH_LENGTH) return;
    if (hash === endUserHash) return;

    // An account switch gets a fresh session, and neither the old surface nor
    // its stored chat leaks into the new identity.
    if (endUserHash) {
      rotateSession();
      ui.reset();
      clearChat(config.key, endUserHash);
    }
    endUserHash = hash;

    // Rebuild before the stream reopens, so a buffered answer lands in the
    // restored answer rather than a blank one.
    ui.restore(loadChat(config.key, hash));

    for (const args of pendingEvents) enqueueEvent(...args);
    pendingEvents = [];

    stream?.close();
    stream = connectStream(config, hash, {
      nudge: failSilent((payload) => ui.showNudge(payload)),
      token: failSilent((messageId, text) => ui.appendAnswerToken(messageId, text)),
      done: failSilent((messageId, text, citations) => ui.finishAnswer(messageId, text, citations)),
      action: failSilent((payload) => {
        ui.notifyUnread();
        showConfirmation(ui.container, payload, {
          onConfirm: async (executionId) => {
            const result = await post(
              config,
              CONFIRMATIONS_PATH,
              JSON.stringify({
                execution_id: executionId,
                session_id: currentSessionId(),
                end_user_hash: endUserHash,
              }),
            );
            return result.ok;
          },
          onCancel: failSilent((executionId) =>
            enqueueInterventionEvent("fr.action_cancelled", executionId),
          ),
        });
      }),
      connected: failSilent((open) => ui.setConnected(open)),
    });
  });

  /** Captures one founder-named event, dropping names outside the grammar. */
  const track = failSilent((event: string, properties?: Properties) => {
    // One rejected name never poisons a batch the gateway validates as a whole.
    if (typeof event !== "string" || !EVENT_NAME.test(event)) return;
    capture(event, properties);
  });

  startAutocapture(failSilent(capture));
  window.fr = { identify, track };
}

// Boots once per page. A second snippet is a no-op.
failSilent(() => {
  if (window.fr) return;
  const config = readConfig();
  if (config) start(config);
})();
