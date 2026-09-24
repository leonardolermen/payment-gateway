package com.gateway.app.security;

import com.gateway.merchants.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer gk_…} on everything under /v1/ except /v1/admin/**
 * and /v1/providers/** (inbound webhooks, authenticated by the provider) and the actuator.
 */
@Component
@Order(20)
public class ApiKeyAuthFilter extends OncePerRequestFilter {
  private final ApiKeyService apiKeys;
  public ApiKeyAuthFilter(ApiKeyService apiKeys) { this.apiKeys = apiKeys; }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !ProtectedRoutes.requiresApiKey(RequestPath.of(req).normalized());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String auth = req.getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) { Problems.write(res, 401, "UNAUTHENTICATED", "send Authorization: Bearer gk_…"); return; }
    var current = apiKeys.authenticate(auth.substring(7).trim());
    if (current.isEmpty()) { Problems.write(res, 401, "UNAUTHENTICATED", "invalid or revoked api key, or suspended merchant"); return; }
    MerchantContext.set(req, new MerchantContext.Current(current.get().merchantId(), current.get().environment(), current.get().apiKeyId()));
    chain.doFilter(req, res);
  }
}
