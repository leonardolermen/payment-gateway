package com.gateway.app.security;

/**
 * The one route predicate {@link ApiKeyAuthFilter} and {@link RateLimitFilter} must agree on.
 * They used to each carry their own copy; if the two ever drifted, {@code RateLimitFilter} could
 * run on a route {@code ApiKeyAuthFilter} had not authenticated and call
 * {@code MerchantContext.current()} with nothing set — an {@code IllegalStateException} thrown from
 * inside a {@code Filter}, which {@code ErrorHandler} (a {@code @RestControllerAdvice}, wired into
 * the dispatcher, not the filter chain) cannot catch: a 500 where the brief calls for a 401.
 * One shared predicate makes that drift impossible instead of merely unlikely.
 */
final class ProtectedRoutes {
  private ProtectedRoutes() {}

  static boolean requiresApiKey(String path) {
    return path.startsWith("/v1/") && !path.startsWith("/v1/admin/") && !path.startsWith("/v1/providers/");
  }
}
