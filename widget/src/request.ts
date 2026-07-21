import { toHex } from "./hex";
import type { Config } from "./types";

const encoder = new TextEncoder();

// Imported once. The raw secret never touches the crypto API twice.
let signingKey: Promise<CryptoKey> | undefined;

/** Signs `{timestamp}.{body}` with HMAC-SHA256 and returns lowercase hex. */
export async function sign(
  secret: string,
  timestamp: string,
  body: string,
): Promise<string> {
  const mac = await crypto.subtle.sign(
    "HMAC",
    await importSigningKey(secret),
    encoder.encode(`${timestamp}.${body}`),
  );
  return toHex(new Uint8Array(mac));
}

/** Whether the request landed, whether resending it could help, and whether the server refused it. */
export interface PostResult {
  ok: boolean;
  retryable: boolean;
  refused: boolean;
}

/**
 * Signs and posts a JSON body with keepalive set, so a flush issued on page
 * hide survives the unload. A 429 or 5xx is retryable and other 4xx are
 * not, because resending the same request cannot improve it. A 5xx or a
 * network failure leaves the request's fate unknown, so neither is a refusal.
 */
export async function post(
  config: Config,
  path: string,
  body: string,
): Promise<PostResult> {
  const timestamp = new Date().toISOString();
  try {
    const response = await fetch(config.host + path, {
      method: "POST",
      keepalive: true,
      headers: {
        "Content-Type": "application/json",
        "X-FirstRun-Key": config.key,
        "X-FirstRun-Timestamp": timestamp,
        "X-FirstRun-Signature": await sign(config.secret, timestamp, body),
      },
      body,
    });
    return {
      ok: response.ok,
      retryable: response.status === 429 || response.status >= 500,
      refused: !response.ok && response.status < 500,
    };
  } catch {
    return { ok: false, retryable: true, refused: false };
  }
}

/** Imports the signing key once and caches the promise. */
function importSigningKey(secret: string): Promise<CryptoKey> {
  signingKey ??= crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return signingKey;
}
