// Fails the build when dist/firstrun.js exceeds the 30 KB gzip budget.
import { readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";

const BUDGET_BYTES = 30 * 1024;

const raw = readFileSync(new URL("../dist/firstrun.js", import.meta.url));
const gzipped = gzipSync(raw, { level: 9 }).length;
const percent = ((gzipped / BUDGET_BYTES) * 100).toFixed(1);

console.log(
  `firstrun.js: ${raw.length} B raw, ${gzipped} B gzipped, ` +
    `budget ${BUDGET_BYTES} B (${percent}% used)`,
);

if (gzipped > BUDGET_BYTES) {
  console.error(">> over the 30 KB gzip budget");
  process.exit(1);
}
