package com.gateway.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;

/**
 * The caller's address for rate limiting. X-Forwarded-For is client-controlled, so it is believed
 * only when the hop that connected to us is our own proxy (loopback or private); otherwise anyone
 * could rotate the header and get a fresh bucket per request.
 *
 * <p>The design assumes exactly one trusted reverse proxy that appends the connecting address (or
 * overwrites the header). Proxies append, so the FIRST entry is whatever the client sent and is
 * attacker-chosen; the LAST is the one our proxy wrote. A chain of proxies would need a
 * trusted-proxy count to skip, which is deferred. A value that is not an IP literal never becomes a
 * bucket key: it falls back to the remote address.
 */
public record ClientIp(String value) {
  public static ClientIp of(HttpServletRequest request) {
    String remote = request.getRemoteAddr();

    if (isOwnProxy(remote)) {
      // A proxy may send the header as several lines; joined, the last entry is still the last.
      String forwarded = String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")));
      String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).strip();

      if (parse(last) != null) {
        return new ClientIp(last);
      }
    }

    return new ClientIp(remote);
  }

  private static boolean isIpLiteral(String address) {
    // Literal addresses only: getByName on a hostname would trigger a DNS lookup.
    if (address.contains(":")) {
      return address.matches("[0-9A-Fa-f:.]+");
    }

    return address.matches("(\\d{1,3}\\.){3}\\d{1,3}")
        && java.util.Arrays.stream(address.split("\\."))
            .allMatch(octet -> Integer.parseInt(octet) <= 255);
  }

  private static boolean isOwnProxy(String address) {
    if (address == null || !isIpLiteral(address)) {
      return false;
    }

    try {
      InetAddress parsed = InetAddress.getByName(address);
      // For IPv4, isSiteLocalAddress covers 10/8, 172.16/12 and 192.168/16 (RFC 1918), so no
      // explicit 172.16/12 check is needed; the tests pin 172.15 and 172.32 as public. IPv6
      // site-local is the deprecated fec0::/10, so fc00::/7 (unique local) is checked by hand.
      return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || isUniqueLocalV6(parsed);
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static boolean isUniqueLocalV6(InetAddress address) {
    byte[] bytes = address.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
  }

  /** Null unless the text is an IPv4/IPv6 literal; the shape check first keeps DNS out of it. */
  private static InetAddress parse(String address) {
    if (address == null || !isIpLiteral(address)) {
      return null;
    }

    try {
      return InetAddress.getByName(address);
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
