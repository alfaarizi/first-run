package com.firstrunhq.decisioning.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Remembers each end user's latest nudge so a chat opened from it recovers the intervention and
 * milestone it answers. Bounded and in-memory: a lost entry only costs the conversation its
 * milestone context, never the answer.
 */
@Component
class NudgeContexts {

  private static final int MAX_ENTRIES = 10_000;

  private final Map<String, NudgeContext> latestByUser =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, NudgeContext> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  void record(UUID appId, String endUserHash, NudgeContext context) {
    synchronized (latestByUser) {
      latestByUser.put(key(appId, endUserHash), context);
    }
  }

  Optional<NudgeContext> latest(UUID appId, String endUserHash) {
    synchronized (latestByUser) {
      return Optional.ofNullable(latestByUser.get(key(appId, endUserHash)));
    }
  }

  private static String key(UUID appId, String endUserHash) {
    return appId + ":" + endUserHash;
  }

  /** What one nudge answered: the intervention and the milestone the user was stuck on. */
  record NudgeContext(UUID nudgeId, UUID milestoneId, String milestoneName) {}
}
