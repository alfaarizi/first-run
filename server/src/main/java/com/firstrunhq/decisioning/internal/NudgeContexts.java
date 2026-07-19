package com.firstrunhq.decisioning.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Remembers each end user's recent nudges so a chat opened from one recovers the intervention and
 * milestone it answers. Bounded and in-memory: a lost entry only costs the conversation its
 * milestone context, never the answer.
 */
@Component
class NudgeContexts {

  private static final int MAX_ENTRIES = 10_000;

  private final Map<String, NudgeContext> latestByUser = boundedLru();
  private final Map<String, NudgeContext> byNudge = boundedLru();

  /** Remembers the nudge as the user's latest and by its id. */
  synchronized void record(UUID appId, String endUserHash, NudgeContext context) {
    latestByUser.put(userKey(appId, endUserHash), context);
    byNudge.put(nudgeKey(appId, endUserHash, context.nudgeId()), context);
  }

  /** The user's most recent nudge, for a conversation opened without a ref. */
  synchronized Optional<NudgeContext> latest(UUID appId, String endUserHash) {
    return Optional.ofNullable(latestByUser.get(userKey(appId, endUserHash)));
  }

  /** The nudge a message's ref names, scoped to its user so a ref cannot borrow another's nudge. */
  synchronized Optional<NudgeContext> find(UUID appId, String endUserHash, UUID nudgeId) {
    return Optional.ofNullable(byNudge.get(nudgeKey(appId, endUserHash, nudgeId)));
  }

  /** Builds an access-ordered map that evicts its eldest entry past the cap. */
  private static Map<String, NudgeContext> boundedLru() {
    return new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, NudgeContext> eldest) {
        return size() > MAX_ENTRIES;
      }
    };
  }

  /** Builds the key holding one user's latest nudge. */
  private static String userKey(UUID appId, String endUserHash) {
    return appId + ":" + endUserHash;
  }

  /** Builds the key holding one specific nudge, scoped to its user. */
  private static String nudgeKey(UUID appId, String endUserHash, UUID nudgeId) {
    return appId + ":" + endUserHash + ":" + nudgeId;
  }

  /** What one nudge answered: the intervention and the milestone the user was stuck on. */
  record NudgeContext(UUID nudgeId, UUID milestoneId, String milestoneName) {}
}
