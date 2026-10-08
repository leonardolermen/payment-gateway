package com.gateway.app.security;

import com.gateway.app.api.checkout.CheckoutProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP limit on the public checkout and auth routes, which have no API key to limit on. Same
 * shape as {@link RateLimitFilter} (in-memory, one instance today). Order 31, right after it: the
 * two never run on the same request, since {@code ProtectedRoutes.requiresApiKey} excludes checkout
 * and auth.
 */
@Component
@Order(31)
public class CheckoutRateLimitFilter extends OncePerRequestFilter {
  private static final int MAX_TRACKED_IPS = 10_000;

  private final CheckoutProperties properties;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public CheckoutRateLimitFilter(CheckoutProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = RequestPath.of(request).normalized();
    return !ProtectedRoutes.isCheckout(path) && !ProtectedRoutes.isAuth(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // A flood of distinct IPs is itself the attack; losing counters under a flood is cheaper than
    // unbounded memory, so the map is simply emptied instead of swept by a background thread.
    if (buckets.size() > MAX_TRACKED_IPS) {
      buckets.clear();
    }

    // Separate buckets per scope: a payer polling a checkout must not lock themselves out of login.
    boolean isAuth = ProtectedRoutes.isAuth(RequestPath.of(request).normalized());
    String key = (isAuth ? "auth|" : "checkout|") + ClientIp.of(request).value();
    int capacity = isAuth ? properties.authRateLimitPerMinute() : properties.rateLimitPerMinute();
    Bucket bucket = buckets.computeIfAbsent(key, ignored -> newBucket(capacity));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

    if (!probe.isConsumed()) {
      long retryAfterSeconds =
          Math.max(1, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
      response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
      // Fixed detail: the path carries the checkout token and must not be echoed or logged.
      Problems.write(
          response,
          429,
          "RATE_LIMITED",
          "too many requests; retry after " + retryAfterSeconds + "s");
      return;
    }

    chain.doFilter(request, response);
  }

  private static Bucket newBucket(int capacity) {
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofMinutes(1))
                .build())
        .build();
  }
}
