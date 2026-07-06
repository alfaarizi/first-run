package com.firstrunhq.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IpTruncatorTests {

  @Test
  void zeroesTheLastIpv4Octet() {
    assertThat(IpTruncator.truncate("12.214.31.144")).isEqualTo("12.214.31.0");
  }

  @Test
  void zeroesTheLastEightyIpv6Bits() {
    assertThat(IpTruncator.truncate("2001:db8:1234:5678:9abc:def0:1234:5678"))
        .isEqualTo("2001:db8:1234:0:0:0:0:0");
  }

  @Test
  void treatsMappedIpv6AsIpv4() {
    assertThat(IpTruncator.truncate("::ffff:12.214.31.144")).isEqualTo("12.214.31.0");
  }

  @Test
  void dropsAnythingThatIsNotAnIpLiteral() {
    assertThat(IpTruncator.truncate(null)).isNull();
    assertThat(IpTruncator.truncate("")).isNull();
    assertThat(IpTruncator.truncate("evil.example.com")).isNull();
    assertThat(IpTruncator.truncate("12.214.31.144:8080")).isNull();
    assertThat(IpTruncator.truncate("999.1.1.1")).isNull();
  }
}
