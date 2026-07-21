package com.firstrunhq.knowledge.internal;

/** Lifecycle of a doc source's index, written by the agent as a crawl runs. */
enum DocSourceStatus {
  PENDING,
  INDEXING,
  READY,
  FAILED
}
