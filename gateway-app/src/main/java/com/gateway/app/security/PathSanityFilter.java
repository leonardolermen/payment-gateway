package com.gateway.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fail-closed: rejects any path whose raw form differs from the routed form ({@code ;}, {@code %},
 * dot segments). The final review reproduced a TEST merchant key listing and creating merchants
 * through {@code GET /v1/%61dmin/merchants} and {@code POST /v1/admin;x/merchants}. The other
 * filters also classify on {@link RequestPath#normalized()}, but no route of this API needs an
 * encoded or matrix-bearing path, so refusing them outright removes the whole class instead of one
 * instance.
 */
@Component
@Order(0)
public class PathSanityFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (RequestPath.of(req).suspicious()) {
      Problems.write(
          res,
          400,
          "INVALID_PATH",
          "path must not contain encoded characters, ';' or dot segments");
      return;
    }
    chain.doFilter(req, res);
  }
}
