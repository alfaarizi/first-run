/**
 * @fileoverview Hand-rolled ingest client following api/openapi/ingest.yaml.
 * The widget SDK replaces this file once it exists. Keep the wire format,
 * not the code.
 */
import { v7 as uuidv7 } from 'uuid'

// Demo credentials from the root .env, matching scripts/seed.sql. The SDK
// key is a public identifier and the HMAC key ships client-side by design.
const SDK_KEY = import.meta.env.VITE_FIRSTRUN_KEY
const HMAC_KEY = import.meta.env.VITE_FIRSTRUN_HMAC_KEY
if (!SDK_KEY || !HMAC_KEY) {
  throw new Error('firstrun: set VITE_FIRSTRUN_KEY and VITE_FIRSTRUN_HMAC_KEY, see compose.yaml')
}

// Real integrations hash the user ID and rotate sessions after 30 idle
// minutes. A per-browser value and per-tab sessions are close enough here.
const endUserHash = stored(localStorage, 'firstrun.end_user_hash', () =>
  crypto.randomUUID().replaceAll('-', ''),
)

const sessionId = stored(sessionStorage, 'firstrun.session_id', uuidv7)

/** Sends a signed event batch to the ingest gateway. */
export async function track(
  event: string,
  properties: Record<string, string | number | boolean> = {},
): Promise<void> {
  const timestamp = new Date().toISOString()
  const body = JSON.stringify({
    sent_at: timestamp,
    events: [
      {
        id: uuidv7(),
        event,
        end_user_hash: endUserHash,
        session_id: sessionId,
        timestamp,
        properties,
      },
    ],
  })
  await fetch('/v1/e', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-FirstRun-Key': SDK_KEY,
      'X-FirstRun-Timestamp': timestamp,
      'X-FirstRun-Signature': await sign(`${timestamp}.${body}`),
    },
    body,
  })
}

const encoder = new TextEncoder()

// A CryptoKey is reusable, so import it once rather than per request.
const hmacKey = crypto.subtle.importKey(
  'raw',
  encoder.encode(HMAC_KEY),
  { name: 'HMAC', hash: 'SHA-256' },
  false,
  ['sign'],
)

// Hex HMAC-SHA256 over `{timestamp}.{raw body}` (api/openapi/ingest.yaml).
async function sign(payload: string): Promise<string> {
  const signature = await crypto.subtle.sign('HMAC', await hmacKey, encoder.encode(payload))
  return [...new Uint8Array(signature)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function stored(storage: Storage, key: string, make: () => string): string {
  const existing = storage.getItem(key)
  if (existing) return existing
  const value = make()
  storage.setItem(key, value)
  return value
}
