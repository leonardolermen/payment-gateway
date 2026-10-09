package com.gateway.app.security;

import com.gateway.app.api.checkout.CheckoutProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF guard for /v1/auth/**. The refresh cookie is SameSite=None (the panel is on another origin),
 * so a browser sends it with a form posted from any site; a page on another origin could then call
 * refresh or logout on the user's behalf. A browser always sends Origin on such a request, so an
 * Origin outside the configured list is refused. No Origin (curl, a server, the tests) passes: the
 * attack needs a browser, and a browser cannot omit the header. With no list configured CORS is off
 * and there is nothing to compare against.
 */
@Component
@Order(18)
public class AuthOriginFilter extends OncePerRequestFilter {
  private final CheckoutProperties properties;

  public AuthOriginFilter(CheckoutProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ProtectedRoutes.isAuth(RequestPath.of(request).normalized());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    boolean isForeignOrigin =
        origin != null
            && !properties.corsOrigins().isEmpty()
            && !properties.corsOrigins().contains(origin);

    if (isForeignOrigin) {
      Problems.write(response, 403, "ORIGIN_NOT_ALLOWED", "this origin may not call /v1/auth");
      return;
    }

    chain.doFilter(request, response);
  }
}
