// The evals/baselines.json pins and floors, resolved relative to this module
// so every consumer reads the same file from any working directory.

import { readFileSync } from 'node:fs'

const BASELINES_URL = new URL('../../evals/baselines.json', import.meta.url)

export const BASELINES = JSON.parse(readFileSync(BASELINES_URL, 'utf8'))

/** Resolves a pinned dataset's URL from its baselines.json entry. */
export function resolveDatasetUrl(pin) {
  return new URL(pin.path, BASELINES_URL)
}
