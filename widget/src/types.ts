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

/** A proposed action awaiting the user's explicit click, with the registry's copy. */
export interface ActionPayload {
  execution_id: string;
  copy: string;
}
