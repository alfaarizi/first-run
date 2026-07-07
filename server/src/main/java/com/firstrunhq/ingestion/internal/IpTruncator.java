package com.firstrunhq.ingestion.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class IpTruncator {

  private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
  private static final Pattern IPV4 = Pattern.compile("(%s\\.){3}%s".formatted(OCTET, OCTET));

  private IpTruncator() {}

  /**
   * Zeroes the host part of an address before anything stores it, the last octet of an IPv4 address
   * and the last 80 bits of an IPv6 address. Returns null for anything else, so a garbage header is
   * dropped rather than stored. An exact-octet match and the bracketed IPv6 form both parse as
   * literals only, and {@link InetAddress#getByName} never falls through to a blocking DNS lookup
   * of a client-supplied name.
   */
  static @Nullable String truncate(@Nullable String ip) {
    if (ip == null) {
      return null;
    }

    String literal = ip.trim();
    boolean ipv6 = literal.indexOf(':') >= 0;
    if (!ipv6 && !IPV4.matcher(literal).matches()) {
      return null;
    }

    byte[] octets;
    try {
      octets = InetAddress.getByName(ipv6 ? "[" + literal + "]" : literal).getAddress();
    } catch (UnknownHostException notAnIpLiteral) {
      return null;
    }

    int kept = octets.length == 4 ? 3 : 6;
    Arrays.fill(octets, kept, octets.length, (byte) 0);
    try {
      return InetAddress.getByAddress(octets).getHostAddress();
    } catch (UnknownHostException impossible) {
      throw new IllegalStateException("truncation preserves address length", impossible);
    }
  }
}
