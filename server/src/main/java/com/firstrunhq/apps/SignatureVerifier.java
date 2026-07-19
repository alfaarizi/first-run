package com.firstrunhq.apps;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies widget request signatures against the app's HMAC key, for every endpoint the SDK talks
 * to.
 */
@Component
public class SignatureVerifier {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Duration TOLERANCE = Duration.ofMinutes(5);

  /**
   * Accepts a hex HMAC-SHA256 over {@code {timestamp}.{raw body}} when the timestamp is within five
   * minutes of now, either direction (api/openapi/ingest.yaml). The comparison is constant-time, so
   * a mismatch leaks no matching-prefix timing.
   */
  public boolean verify(String hmacKey, String timestamp, byte[] body, String signatureHex) {
    Instant clientTime;
    byte[] claimed;
    try {
      clientTime = Instant.parse(timestamp);
      claimed = HexFormat.of().parseHex(signatureHex);
    } catch (RuntimeException malformedHeader) {
      return false;
    }
    if (Duration.between(clientTime, Instant.now()).abs().compareTo(TOLERANCE) > 0) {
      return false;
    }
    return MessageDigest.isEqual(claimed, hmac(hmacKey, timestamp, body));
  }

  /** Computes the expected HMAC over {@code {timestamp}.{body}}. */
  private static byte[] hmac(String key, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(body);
      return mac.doFinal();
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(HMAC_ALGORITHM + " is a required JCA algorithm", impossible);
    }
  }
}
