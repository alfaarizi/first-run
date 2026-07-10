// JSON Lines reading shared by the dataset scripts.

import { readFileSync } from 'node:fs'

/** Reads a JSON Lines file into an array of rows. */
export function readJsonl(path) {
  return readFileSync(path, 'utf8')
    .split('\n')
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line))
}
