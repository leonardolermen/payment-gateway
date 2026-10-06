package com.gateway.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The caller's address for rate limiting. X-Forwarded-For is client-controlled, so it is believed
 * only when the hop that connected to us is our own proxy (loopback or private); otherwise anyone
 * could rotate the header and get a fresh bucket per request.
 */
public record ClientIp(String value) {
  public static ClientIp of(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    String forwarded = request.getHeader("X-Forwarded-For");

    if (forwarded != null && isOwnProxy(remote)) {
      String first = forwarded.split(",", 2)[0].strip();
      if (!first.isEmpty()) {
        return new ClientIp(first);
      }
    }

    return new ClientIp(remote);
  }

  private static boolean isOwnProxy(String address) {
    if (address == null || address.isBlank()) {
      return false;
    }

    // Literal addresses only: getByName on a hostname would trigger a DNS lookup.
    if (!address.contains(":") && !address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
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
}
