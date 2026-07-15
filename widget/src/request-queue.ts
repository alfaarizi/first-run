import { INGEST_PATH } from "./constants";
import { post } from "./request";
import type { CapturedEvent, Config } from "./types";

const FLUSH_AT = 20;
const FLUSH_INTERVAL_MS = 5000;
const MAX_BATCH = 50;
const MAX_ATTEMPTS = 3;
const BACKOFF_MS = 2000;

// margin under the ingest contract's 64 KB body cap
const MAX_BODY_BYTES = 60 * 1024;
// the Segment per-message cap, an event past it can never be delivered
const MAX_EVENT_BYTES = 32 * 1024;

const encoder = new TextEncoder();

interface QueuedEvent {
  event: CapturedEvent;
  bytes: number;
}

/**
 * Batches events under the contract's count and body caps. A retryable
 * failure backs off before the batch drops, and the gateway dedupes on
 * each event's id, so retries and the page-hide flush overlap safely.
 */
export class RequestQueue {
  private readonly config: Config;
  private queue: QueuedEvent[] = [];
  private timer: number | undefined;
  private attempts = 0;
  private sending = false;

  constructor(config: Config) {
    this.config = config;
    addEventListener("pagehide", () => this.flushOnHide());
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") this.flushOnHide();
    });
  }

  enqueue(event: CapturedEvent): void {
    const bytes = encoder.encode(JSON.stringify(event)).length;
    if (bytes > MAX_EVENT_BYTES) return;

    this.queue.push({ event, bytes });

    // a scheduled retry owns the queue while backing off
    if (this.queue.length >= FLUSH_AT && this.attempts === 0) void this.flush();
    else this.timer ??= setTimeout(() => void this.flush(), FLUSH_INTERVAL_MS);
  }

  private async flush(): Promise<void> {
    clearTimeout(this.timer);
    this.timer = undefined;
    if (this.sending || this.queue.length === 0) return;

    const batch = this.nextBatch();
    this.sending = true;

    const result = await post(this.config, INGEST_PATH, batchBody(batch));
    this.sending = false;

    if (result.ok || !result.retryable || ++this.attempts >= MAX_ATTEMPTS) {
      this.queue.splice(0, batch.length);
      this.attempts = 0;
      if (this.queue.length > 0) {
        this.timer = setTimeout(() => void this.flush(), FLUSH_INTERVAL_MS);
      }
    } else {
      this.timer = setTimeout(() => void this.flush(), BACKOFF_MS * this.attempts);
    }
  }

  private nextBatch(offset = 0): CapturedEvent[] {
    const batch: CapturedEvent[] = [];
    let bytes = 0;
    for (let i = offset; i < this.queue.length; i++) {
      const queued = this.queue[i];
      if (batch.length === MAX_BATCH || bytes + queued.bytes > MAX_BODY_BYTES) break;

      bytes += queued.bytes;
      batch.push(queued.event);
    }
    return batch;
  }

  // events stay queued because the id dedupe makes a second send safe, and
  // the browser's keepalive quota bounds how much of the tail can go
  private flushOnHide(): void {
    for (let offset = 0; offset < this.queue.length; ) {
      const batch = this.nextBatch(offset);
      if (batch.length === 0) break;

      void post(this.config, INGEST_PATH, batchBody(batch));
      offset += batch.length;
    }
  }
}

function batchBody(events: CapturedEvent[]): string {
  return JSON.stringify({ sent_at: new Date().toISOString(), events });
}
