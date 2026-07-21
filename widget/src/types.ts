/** Widget configuration, read once from the snippet's data attributes. */
export interface Config {
  /** The app's SDK public key. An identifier, not a secret. */
  key: string;
  /**
   * The app's rotatable signing key. It ships with the snippet, so the
   * origin allowlist and rate limits carry the abuse defense.
   */
  secret: string;
  /** Gateway origin, no trailing slash. */
  host: string;
  /**
   * Property keys the app allows on custom events, checked client-side
   * while the gateway stays authoritative.
   */
  allowlist: Set<string>;
}

/** Scalar values only, matching what the ingest contract accepts. */
export type Properties = Record<string, string | number | boolean>;

/** One event in an ingest batch. */
export interface CapturedEvent {
  id: string;
  event: string;
  end_user_hash: string;
  session_id: string;
  timestamp: string;
  /** The server-issued entity an intervention event responds to, a nudge or an action execution. */
  ref?: string;
  properties?: Properties;
}

/** A server-rendered nudge. The widget renders text as text, never as markup. */
export interface NudgePayload {
  id: string;
  text: string;
}

/** A source the answer cites. */
export interface Citation {
  title: string;
  url: string;
}

/** The data of one `token` stream frame: a span of the answer to one message. */
export interface TokenFrame {
  message_id: string;
  text: string;
}

/** The data of the `done` stream frame closing one message's answer. */
export interface DoneFrame {
  message_id: string;
  /** The complete answer, so tokens a reconnect or reload dropped heal here. */
  text?: string;
  citations?: Citation[];
}

/** One rendered chat message, as the surface persists and restores it. */
export interface ChatMessage {
  who: "user" | "agent";
  text: string;
  /** Epoch milliseconds, so a restored message keeps its original time. */
  at: number;
  citations?: Citation[];
}

/** The chat surface's persistable state, restored across a page reload. */
export interface ChatSnapshot {
  open: boolean;
  messages: ChatMessage[];
  /** The in-flight answer's id, so a reload restores its typing indicator. */
  pendingId?: string;
  /** The nudge the conversation opened from, so a reload keeps the reply's ref. */
  engagedNudgeId?: string;
  /** The previewed nudge the user has not acted on, so a reload keeps its bubble. */
  previewNudge?: NudgePayload;
  /** The open-panel nudges awaiting a reply, so a reload keeps their engagement. */
  awaitingNudgeIds?: string[];
  /** The unsent composer text, so a reload keeps a half-typed question. */
  composerDraft?: string;
  /** Whether the composer held the caret. */
  composerFocused?: boolean;
  /** The message log's scroll offset while open, so a reload keeps the view. */
  scrollTop?: number;
}

/** A proposed action awaiting the user's explicit click, with the registry's copy. */
export interface ActionPayload {
  execution_id: string;
  copy: string;
}
