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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
  // One map per scope: a flood that empties the checkout map must not also reset login counters,
  // or the cheapest way to brute-force a password is to spray checkout from many addresses first.
  private final ConcurrentHashMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> checkoutBuckets = new ConcurrentHashMap<>();

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
    // Separate buckets per scope: a payer polling a checkout must not lock themselves out of login.
    boolean isAuth = ProtectedRoutes.isAuth(RequestPath.of(request).normalized());
    ConcurrentHashMap<String, Bucket> buckets = isAuth ? authBuckets : checkoutBuckets;
    int capacity = isAuth ? properties.authRateLimitPerMinute() : properties.rateLimitPerMinute();

    // A flood of distinct IPs is itself the attack; losing counters under a flood is cheaper than
    // unbounded memory, so the map is simply emptied instead of swept by a background thread.
    if (buckets.size() > MAX_TRACKED_IPS) {
      buckets.clear();
    }

    String key = bucketKey(ClientIp.of(request).value());
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

  /**
   * An IPv6 client usually owns a whole /64: keyed by full address, it would rotate through 2^64
   * buckets and never be limited. The first four hextets are the key; IPv4 stays as is.
   */
  static String bucketKey(String address) {
    if (!address.contains(":")) {
      return address;
    }

    try {
      if (!(InetAddress.getByName(address) instanceof Inet6Address inet6)) {
        return address;
      }

      byte[] bytes = inet6.getAddress();
      StringBuilder prefix = new StringBuilder();
      for (int i = 0; i < 8; i += 2) {
        prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)));
        prefix.append(':');
      }

      return prefix.append(":/64").toString();
    } catch (UnknownHostException e) {
      return address;
    }
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
