// Shared randomness for repo scripts, hand-rolled because scripts/ resolves
// no npm packages. The UUIDv7 builder takes its random bits from the caller:
// seeded bits keep fixtures byte-stable, crypto bits suit live traffic.

/** Returns a seeded mulberry32 PRNG. */
export function mulberry32(seed) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 2 ** 32
  }
}

/**
 * Builds a UUIDv7 (RFC 9562) at a past timestamp. `bits` is 16 bytes whose
 * positions 6-15 supply the random content; the masks set version and
 * variant.
 */
export function uuidv7(atMs, bits) {
  let ms = BigInt(Math.floor(atMs))
  for (let index = 5; index >= 0; index--) {
    bits[index] = Number(ms & 0xffn)
    ms >>= 8n
  }
  bits[6] = (bits[6] & 0x0f) | 0x70
  bits[8] = (bits[8] & 0x3f) | 0x80
  const hex = [...bits].map((byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
