import type { Config } from "./types";

const encoder = new TextEncoder();
let signingKey: Promise<CryptoKey> | undefined;

/** Signs `{timestamp}.{body}` with HMAC-SHA256 and returns lowercase hex. */
export async function sign(
  secret: string,
  timestamp: string,
  body: string,
): Promise<string> {
  const mac = await crypto.subtle.sign(
    "HMAC",
    await importKey(secret),
    encoder.encode(`${timestamp}.${body}`),
  );

  let hex = "";
  for (const byte of new Uint8Array(mac)) hex += byte.toString(16).padStart(2, "0");
  return hex;
}

export interface PostResult {
  ok: boolean;
  retryable: boolean;
}

/**
 * Signs and posts a JSON body with keepalive set, so a flush issued on page
 * hide survives the unload. A 429 or 5xx is retryable and other 4xx are
 * not, because resending the same request cannot improve it.
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
    return { ok: response.ok, retryable: response.status === 429 || response.status >= 500 };
  } catch {
    return { ok: false, retryable: true };
  }
}

function importKey(secret: string): Promise<CryptoKey> {
  signingKey ??= crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return signingKey;
}
