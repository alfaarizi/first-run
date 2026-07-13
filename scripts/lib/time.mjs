// Time parsing and formatting shared by the dataset scripts.

const RFC3339_UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/

/** Formats a duration as minutes and seconds, hours folded into the minutes. */
export function formatDuration(seconds) {
  const wholeSeconds = Math.round(seconds)
  return `${Math.floor(wholeSeconds / 60)}:${String(wholeSeconds % 60).padStart(2, '0')}`
}

/**
 * Parses an RFC 3339 UTC date-time to epoch ms, or NaN. The regex fixes the
 * shape and the round-trip rejects rolled-over dates like Feb 30.
 */
export function parseRfc3339Utc(value) {
  if (typeof value !== 'string' || !RFC3339_UTC.test(value)) return NaN
  const ms = Date.parse(value)
  return Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 19) === value.slice(0, 19) ? ms : NaN
}
