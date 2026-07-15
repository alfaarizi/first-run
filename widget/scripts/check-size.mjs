// Fails the build when dist/firstrun.js exceeds the 30 KB gzip budget.
import { readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";

const BUDGET_BYTES = 30 * 1024;

const raw = readFileSync(new URL("../dist/firstrun.js", import.meta.url));
const gzipped = gzipSync(raw, { level: 9 }).length;
const percent = ((gzipped / BUDGET_BYTES) * 100).toFixed(1);
const toKB = (bytes) => (bytes / 1024).toFixed(2);

console.log(
  `firstrun.js: ${toKB(raw.length)} KB raw, ${toKB(gzipped)} KB gzipped, ` +
    `budget ${BUDGET_BYTES / 1024} KB (${percent}% used)`,
);

if (gzipped > BUDGET_BYTES) {
  console.error(">> over the 30 KB gzip budget");
  process.exit(1);
}
