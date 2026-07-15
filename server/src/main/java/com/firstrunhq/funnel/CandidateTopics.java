package com.firstrunhq.funnel;

import com.firstrunhq.ingestion.EventTopics;

/** Names the Kafka topics the funnel module produces, for the stuck gate and consumers alike. */
public final class CandidateTopics {

  /** Carries every flagged window as one {@link CandidateEnvelope} record. */
  public static final String INTERVENTION_CANDIDATES = "intervention.candidates";

  public static final String INTERVENTION_CANDIDATES_DLQ =
      INTERVENTION_CANDIDATES + EventTopics.DLQ_SUFFIX;

  private CandidateTopics() {}
}
