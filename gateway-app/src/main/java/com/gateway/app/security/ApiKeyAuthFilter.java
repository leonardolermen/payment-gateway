package com.gateway.app.security;

import com.gateway.merchants.apikey.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer gk_…} on everything under /v1/ except /v1/admin/** and
 * /v1/providers/** (inbound webhooks, authenticated by the provider) and the actuator.
 */
@Component
@Order(20)
public class ApiKeyAuthFilter extends OncePerRequestFilter {
  private final ApiKeyService apiKeys;

  public ApiKeyAuthFilter(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ProtectedRoutes.requiresApiKey(RequestPath.of(request).normalized());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // UserSessionFilter (order 19) already authenticated a panel session on this request.
    if (request.getAttribute(MerchantContext.ATTRIBUTE) != null) {
      chain.doFilter(request, response);
      return;
    }

    String auth = request.getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      Problems.write(response, 401, "UNAUTHENTICATED", "send Authorization: Bearer gk_…");
      return;
    }
    var current = apiKeys.authenticate(auth.substring(7).trim());
    if (current.isEmpty()) {
      Problems.write(
          response, 401, "UNAUTHENTICATED", "invalid or revoked api key, or suspended merchant");
      return;
    }
    MerchantContext.set(
        request,
        new MerchantContext.Current(
            current.get().merchantId(),
            current.get().environment(),
            new Actor.ApiKey(current.get().apiKeyId())));

    if (RoleRoutes.userOnly(RequestPath.of(request).normalized())) {
      Problems.write(
          response,
          403,
          "USER_SESSION_REQUIRED",
          "this route is for a signed-in user, not an api key");
      return;
    }

    chain.doFilter(request, response);
  }
}
