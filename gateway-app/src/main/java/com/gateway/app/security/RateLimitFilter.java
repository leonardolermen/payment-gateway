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
 * One bucket per API key, in memory: there is a single instance of this service today (spec §8), so
 * a process-local map is exact. The day there is more than one instance this moves to Redis, because
 * an in-memory bucket per instance would let a merchant multiply its limit by the instance count.
 *
 * <p>Runs after {@link ApiKeyAuthFilter} (@Order(20) then 30) and only on the routes that filter
 * covers, since the key id it limits on comes from {@link MerchantContext}.
 */
@Component
@Order(30)
public class RateLimitFilter extends OncePerRequestFilter {
  private final AppProperties props;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(AppProperties props) { this.props = props; }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return !p.startsWith("/v1/") || p.startsWith("/v1/admin/") || p.startsWith("/v1/providers/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String apiKeyId = MerchantContext.current().apiKeyId();
    Bucket bucket = buckets.computeIfAbsent(apiKeyId, id -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      long retryAfterSeconds = Math.max(1, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
      res.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
      Problems.write(res, 429, "RATE_LIMITED", "too many requests; retry after " + retryAfterSeconds + "s");
      return;
    }
    chain.doFilter(req, res);
  }

  private Bucket newBucket() {
    int n = props.rateLimit().requestsPerMinute();
    return Bucket.builder().addLimit(Bandwidth.builder().capacity(n).refillGreedy(n, Duration.ofMinutes(1)).build()).build();
  }
}
