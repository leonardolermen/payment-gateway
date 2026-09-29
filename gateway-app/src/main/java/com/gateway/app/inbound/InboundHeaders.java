package com.gateway.app.inbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** The request headers a notification keeps in the inbox. */
public final class InboundHeaders {
  private InboundHeaders() {}

  /**
   * A whitelist, never the full header set: what an operator needs to trace a delivery back to the
   * provider and nothing else. The Cielo's notification key header authenticates the call and must
   * never land in the inbox, and neither may any header a provider adds later; the Itaú's
   * certificate fields are added by its controller, by name, for the same reason.
   */
  public static Map<String, String> traced(HttpServletRequest request) {
    Map<String, String> traced = new LinkedHashMap<>();
    put(traced, "X-Correlation-Id", request.getHeader("X-Correlation-Id"));
    put(traced, "User-Agent", request.getHeader("User-Agent"));
    put(traced, "Content-Type", request.getContentType());
    return traced;
  }

  private static void put(Map<String, String> traced, String name, String value) {
    if (value != null) {
      traced.put(name, value);
    }
  }
}
