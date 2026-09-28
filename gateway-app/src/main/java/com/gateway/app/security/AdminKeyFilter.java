package com.gateway.app.security;

import com.gateway.app.AppConfiguration.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /v1/admin/**} with a static {@code X-Admin-Key}. An unset {@code gateway.admin-key}
 * closes the admin surface instead of opening it — "empty means no restriction" is exactly the
 * default that turns a misconfigured deploy into an open admin API.
 */
@Component
@Order(10)
public class AdminKeyFilter extends OncePerRequestFilter {
  private final AppProperties properties;

  public AdminKeyFilter(AppProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ProtectedRoutes.isAdmin(RequestPath.of(request).normalized());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String configured = properties.adminKey();
    String supplied = request.getHeader("X-Admin-Key");
    if (configured == null
        || configured.isBlank()
        || supplied == null
        || !MessageDigest.isEqual(
            configured.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8))) {
      Problems.write(response, 403, "FORBIDDEN", "invalid or missing X-Admin-Key");
      return;
    }
    chain.doFilter(request, response);
  }
}
