package com.firstrunhq.decisioning.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers whether an end user sits in their tenant's holdout, the control group lift is measured
 * against. Membership is a deterministic hash bucket, so it holds without a stored assignment and
 * can never drift, and the tenant seeds the hash, so one user's bucket at one customer says nothing
 * about their bucket at another.
 */
@Component
class Holdouts {

  private static final int BUCKET_COUNT = 10_000;

  // A tenth of each tenant's end users, fixed until per-tenant configuration is added.
  private static final int HOLDOUT_BUCKETS = 1_000;

  boolean contains(UUID tenantId, String endUserHash) {
    byte[] digest = sha256(tenantId + ":" + endUserHash);
    return Math.floorMod(ByteBuffer.wrap(digest).getInt(), BUCKET_COUNT) < HOLDOUT_BUCKETS;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
