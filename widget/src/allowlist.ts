import type { Properties } from "./types";

const MAX_PROPERTIES = 20;

/** Keeps allowlisted scalar properties, capped at the ingest contract's 20. */
export function filterProperties(
  properties: Properties,
  allowlist: Set<string>,
): Properties {
  const kept: Properties = {};
  let count = 0;
  for (const [key, value] of Object.entries(properties)) {
    const kind = typeof value;
    if (!allowlist.has(key)) continue;
    if (kind !== "string" && kind !== "number" && kind !== "boolean") continue;
    // JSON turns non-finite numbers into null, which the contract rejects
    if (kind === "number" && !Number.isFinite(value)) continue;
    if (++count > MAX_PROPERTIES) break;
    kept[key] = value;
  }
  return kept;
}
