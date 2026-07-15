import { INGEST_PATH } from "./constants";
import { beacon, post } from "./request";
import type { CapturedEvent, Config } from "./types";

const BATCH_SIZE = 20;
const FLUSH_MS = 5000;
const MAX_BATCH = 50;
const MAX_ATTEMPTS = 3;
const BACKOFF_MS = 2000;

/**
 * Batches events and flushes at 20 events or 5 seconds. A retryable failure
 * backs off and drops the batch after 3 attempts. The gateway dedupes on
 * each event's id, so retries and the page-hide beacon overlap safely.
 */
export class RequestQueue {
  private readonly config: Config;
  private queue: CapturedEvent[] = [];
  private timer: number | undefined;
  private attempts = 0;
  private sending = false;

  constructor(config: Config) {
    this.config = config;
    addEventListener("pagehide", () => this.flushWithBeacon());
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") this.flushWithBeacon();
    });
  }

  enqueue(event: CapturedEvent): void {
    this.queue.push(event);
    if (this.queue.length >= BATCH_SIZE) void this.flush();
    else this.timer ??= setTimeout(() => void this.flush(), FLUSH_MS);
  }

  private async flush(): Promise<void> {
    clearTimeout(this.timer);
    this.timer = undefined;
    if (this.sending || this.queue.length === 0) return;

    const batch = this.queue.slice(0, MAX_BATCH);
    this.sending = true;

    const result = await post(this.config, INGEST_PATH, batchBody(batch));
    this.sending = false;

    if (result.ok || !result.retryable || ++this.attempts >= MAX_ATTEMPTS) {
      this.queue.splice(0, batch.length);
      this.attempts = 0;
      if (this.queue.length > 0) {
        this.timer = setTimeout(() => void this.flush(), FLUSH_MS);
      }
    } else {
      this.timer = setTimeout(() => void this.flush(), BACKOFF_MS * this.attempts);
    }
  }

  private flushWithBeacon(): void {
    if (this.queue.length === 0) return;
    const batch = this.queue.splice(0, MAX_BATCH);
    void beacon(this.config, INGEST_PATH, batchBody(batch));
  }
}

function batchBody(events: CapturedEvent[]): string {
  return JSON.stringify({ sent_at: new Date().toISOString(), events });
}
