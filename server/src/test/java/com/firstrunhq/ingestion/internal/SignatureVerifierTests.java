package com.firstrunhq.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTests {

  private static final String KEY = "fr_sk_local_test";
  private static final byte[] BODY = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

  private final SignatureVerifier verifier = new SignatureVerifier();

  @Test
  void acceptsFreshSignedPayload() {
    String timestamp = Instant.now().toString();
    assertThat(verifier.verify(KEY, timestamp, BODY, sign(KEY, timestamp, BODY))).isTrue();
  }

  @Test
  void acceptsClockSkewInsideTheWindow() {
    String timestamp = Instant.now().plus(Duration.ofMinutes(2)).toString();
    assertThat(verifier.verify(KEY, timestamp, BODY, sign(KEY, timestamp, BODY))).isTrue();
  }

  @Test
  void rejectsTamperedBody() {
    String timestamp = Instant.now().toString();
    String signature = sign(KEY, timestamp, BODY);
    byte[] tampered = "{\"events\":[{}]}".getBytes(StandardCharsets.UTF_8);
    assertThat(verifier.verify(KEY, timestamp, tampered, signature)).isFalse();
  }

  @Test
  void rejectsWrongKey() {
    String timestamp = Instant.now().toString();
    assertThat(verifier.verify(KEY, timestamp, BODY, sign("other_key", timestamp, BODY))).isFalse();
  }

  @Test
  void rejectsTimestampOutsideTheWindow() {
    String stale = Instant.now().minus(Duration.ofMinutes(6)).toString();
    assertThat(verifier.verify(KEY, stale, BODY, sign(KEY, stale, BODY))).isFalse();

    String future = Instant.now().plus(Duration.ofMinutes(6)).toString();
    assertThat(verifier.verify(KEY, future, BODY, sign(KEY, future, BODY))).isFalse();
  }

  @Test
  void rejectsMalformedHeaders() {
    String timestamp = Instant.now().toString();
    assertThat(verifier.verify(KEY, "yesterday", BODY, sign(KEY, timestamp, BODY))).isFalse();
    assertThat(verifier.verify(KEY, timestamp, BODY, "not-hex")).isFalse();
  }

  static String sign(String key, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(body);
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
