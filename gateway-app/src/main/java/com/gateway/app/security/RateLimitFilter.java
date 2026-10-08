package com.gateway.app.security;

import com.gateway.app.AppConfiguration.AppProperties;
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
 * One bucket per caller (API key or user), in memory: there is a single instance of this service
 * today (spec §8), so a process-local map is exact. The day there is more than one instance this
 * moves to Redis, because an in-memory bucket per instance would let a merchant multiply its limit
 * by the instance count.
 *
 * <p>Runs after {@link ApiKeyAuthFilter} (@Order(20) then 30) and only on the routes that filter
 * covers, since the actor id it limits on comes from {@link MerchantContext}.
 */
@Component
@Order(30)
public class RateLimitFilter extends OncePerRequestFilter {
  private final AppProperties properties;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(AppProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ProtectedRoutes.requiresApiKey(RequestPath.of(request).normalized());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String actorId = MerchantContext.current().actor().id();
    Bucket bucket = buckets.computeIfAbsent(actorId, id -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      long retryAfterSeconds =
          Math.max(1, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
      response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
      Problems.write(
          response,
          429,
          "RATE_LIMITED",
          "too many requests; retry after " + retryAfterSeconds + "s");
      return;
    }
    chain.doFilter(request, response);
  }

  private Bucket newBucket() {
    int n = properties.rateLimit().requestsPerMinute();
    return Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(n).refillGreedy(n, Duration.ofMinutes(1)).build())
        .build();
  }
}
