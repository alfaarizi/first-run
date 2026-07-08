package com.firstrunhq.ingestion;

/** Names the Kafka topics the ingestion module produces, for the gateway and consumers alike. */
public final class EventTopics {

  /** Carries every accepted event as one {@link EventEnvelope} record. */
  public static final String EVENTS_RAW = "events.raw";

  /** Appended to a topic's name to form its dead-letter queue. */
  public static final String DLQ_SUFFIX = ".dlq";

  public static final String EVENTS_RAW_DLQ = EVENTS_RAW + DLQ_SUFFIX;

  private EventTopics() {}
}
