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
 * Signs and posts a JSON body. A 5xx or network failure is retryable and a
 * 4xx is not, because resending the same request cannot improve it.
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
    return { ok: response.ok, retryable: response.status >= 500 };
  } catch {
    return { ok: false, retryable: true };
  }
}

/**
 * Fires a beacon on page hide. Beacons cannot carry headers, so the
 * signature rides the query string.
 */
export async function beacon(
  config: Config,
  path: string,
  body: string,
): Promise<void> {
  const timestamp = new Date().toISOString();
  const signature = await sign(config.secret, timestamp, body);
  const query =
    `?key=${encodeURIComponent(config.key)}` +
    `&ts=${encodeURIComponent(timestamp)}&sig=${signature}`;

  navigator.sendBeacon(
    config.host + path + query,
    new Blob([body], { type: "application/json" }),
  );
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
