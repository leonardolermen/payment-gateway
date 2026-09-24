package com.gateway.app.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a minimal {@code application/problem+json} body straight to the response, for the filters
 * that run before any {@code @RestControllerAdvice} can see the request (auth, admin key, rate
 * limit). Escaping is limited to the one character ({@code "}) that a code or a static message can
 * ever contain here; the request-derived reason phrases we pass in are all literals.
 */
final class Problems {
  private Problems() {}

  static void write(HttpServletResponse res, int status, String code, String detail) throws IOException {
    res.setStatus(status);
    res.setContentType("application/problem+json");
    res.getWriter().write(String.format(
        "{\"type\":\"urn:gateway:%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}",
        code, code, status, escape(detail)));
  }

  /** Minimal JSON string escaper: backslash and quote must be escaped, and control chars are illegal raw in JSON. */
  private static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
      }
    }
    return out.toString();
  }
}
