/**
 * Generates a UUIDv7, a 48-bit millisecond timestamp followed by random
 * bits, so ids sort by time and the gateway can dedupe on them.
 */
export function uuidv7(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let ms = Date.now();
  for (let i = 5; i >= 0; i--) {
    bytes[i] = ms & 0xff;
    ms = Math.floor(ms / 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  let out = "";
  for (let i = 0; i < 16; i++) {
    out += bytes[i].toString(16).padStart(2, "0");
    if (i === 3 || i === 5 || i === 7 || i === 9) out += "-";
  }
  return out;
}
