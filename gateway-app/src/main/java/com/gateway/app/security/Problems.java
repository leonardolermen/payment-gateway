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
        code, code, status, detail.replace("\"", "'")));
  }
}
