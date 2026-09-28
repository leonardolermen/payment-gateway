package com.gateway.app.api.support;

import com.gateway.app.security.MerchantContext;
import com.gateway.app.security.Problems;
import com.gateway.app.security.RequestPath;
import com.gateway.payments.idempotency.IdempotencyKey;
import com.gateway.payments.idempotency.IdempotencyService;
import com.gateway.payments.idempotency.IdempotencyService.Outcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * The idempotency-key protocol at the HTTP edge (spec section 3.2). The key row is written BEFORE
 * the controller runs and is the lock; the response is stored after. A replay is answered from
 * that row and never reaches the controller — and so never reaches the bank.
 *
 * <p>Runs after {@code ApiKeyAuthFilter} (20) and {@code RateLimitFilter} (30): keys are scoped per
 * merchant, and a rate-limited request must not leave an IN_PROGRESS row behind.
 *
 * <p>The request body is read up front into memory rather than through
 * {@code ContentCachingRequestWrapper}: that wrapper only caches what the controller has already
 * consumed, and the hash is needed before the controller runs.
 */
@Component
@Order(40)
public class IdempotencyFilter extends OncePerRequestFilter {
  /** Set by the controllers so the stored row knows which resource it produced; stripped before the client sees it. */
  public static final String RESOURCE_ID_HEADER = "X-Resource-Id";
  static final String KEY_HEADER = "Idempotency-Key";
  static final String REPLAYED_HEADER = "Idempotent-Replayed";
  /** idempotency_keys.key is VARCHAR(128) and holds "LIVE:" + the client's key; longer would fail at the insert as a 500. */
  private static final int MAX_KEY_LENGTH = 123;

  private static final Pattern PAYMENT_ACTION = Pattern.compile("^/v1/payments/[^/]+/(cancel|refunds|capture)$");

  private final IdempotencyService idempotency;
  private final byte[] hmacKey;

  /**
   * The HMAC key falls back to the API-key pepper so a deployment without the new variable keeps
   * working; setting its own key lets either secret rotate without the other (DECISOES 2026-09-28).
   */
  public IdempotencyFilter(
      IdempotencyService idempotency,
      @Value("${gateway.idempotency.hmac-key:}") String hmacKey,
      @Value("${gateway.api-key-pepper:}") String pepper) {
    this.idempotency = idempotency;
    this.hmacKey = (hmacKey.isBlank() ? pepper : hmacKey).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) {
      return true;
    }
    String path = RequestPath.of(request).normalized();
    return !(path.equals("/v1/payments") || PAYMENT_ACTION.matcher(path).matches());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String key = request.getHeader(KEY_HEADER);
    if (key == null || key.isBlank()) {
      Problems.write(response, 400, "IDEMPOTENCY_KEY_REQUIRED", "this request requires an Idempotency-Key header");
      return;
    }
    if (key.length() > MAX_KEY_LENGTH) {
      Problems.write(response, 400, "INVALID_REQUEST", "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
      return;
    }
    byte[] body = request.getInputStream().readAllBytes();
    String path = RequestPath.of(request).normalized();

    // Scoped by environment too: the key row's PK is (merchant, key), and a LIVE request repeating a
    // TEST request's key and body would otherwise replay the TEST payment as if it were live. The
    // prefix separates the rows; the environment in the hash makes a mix-up a Mismatch, never a replay.
    MerchantContext.Current who = MerchantContext.current();
    String scopedKey = who.environment().name() + ":" + key;
    String hash = IdempotencyKey.hashOf(
        who.environment().name() + " " + request.getMethod() + " " + path + "\n" + new String(body, StandardCharsets.UTF_8),
        hmacKey);
    Outcome outcome = idempotency.begin(who.merchantId(), scopedKey, hash);
    switch (outcome) {
      case Outcome.Replayed(IdempotencyService.Replay r) -> replay(response, r);
      case Outcome.InProgress() ->
          // The old text said "retry with a new Idempotency-Key" first: for an interrupted create that
          // is exactly how a client pays the same order twice. Check first, new key only if nothing exists.
          Problems.write(response, 409, "IN_PROGRESS", "A request with this Idempotency-Key is still in progress or was interrupted by a server error."
                  + " Check the resource with GET /v1/payments?reference=… before retrying; only use a new Idempotency-Key if no payment exists.");
      case Outcome.Mismatch() ->
          Problems.write(response, 422, "IDEMPOTENCY_KEY_REUSED", "this Idempotency-Key was already used with a different request");
      case Outcome.Proceed(IdempotencyKey k) -> proceed(new CachedBodyRequest(request, body), response, chain, k);
    }
  }

  private void proceed(HttpServletRequest request, HttpServletResponse response, FilterChain chain, IdempotencyKey k) throws ServletException, IOException {
    ResourceIdCapturingResponse capturing = new ResourceIdCapturingResponse(response);
    ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(capturing);
    try {
      chain.doFilter(request, cached);
      int status = cached.getStatus();
      // 2xx and 4xx are answers: replaying them is exactly what the client is owed. A 5xx (or an
      // exception escaping the chain) is not finished on purpose: the key stays IN_PROGRESS until the
      // TTL purge, so a retry gets 409 instead of running again. Running again is the dangerous
      // option — createCharge gives every attempt a new txid, so a charge that reached the bank
      // before the failure would be charged twice. The client resolves it with GET and a new key.
      if (status < 500) {
        String stored = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
        idempotency.finish(k, status, stored, capturing.resourceId);
      }
    } finally {
      cached.copyBodyToResponse();
    }
  }

  private static void replay(HttpServletResponse response, IdempotencyService.Replay r) throws IOException {
    response.setStatus(r.code());
    response.setHeader(REPLAYED_HEADER, "true");
    // Content type is not stored; every body this filter stores is JSON, a problem for 4xx.
    response.setContentType(r.code() >= 400 ? "application/problem+json" : "application/json");
    if (r.body() != null) {
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(r.body());
    }
  }

  /** Holds the X-Resource-Id the controller set instead of passing it to the client. */
  private static final class ResourceIdCapturingResponse extends HttpServletResponseWrapper {
    String resourceId;

    ResourceIdCapturingResponse(HttpServletResponse response) { super(response); }

    @Override
    public void setHeader(String name, String value) {
      if (RESOURCE_ID_HEADER.equalsIgnoreCase(name)) {
        resourceId = value;
      } else {
        super.setHeader(name, value);
      }
    }

    @Override
    public void addHeader(String name, String value) {
      if (RESOURCE_ID_HEADER.equalsIgnoreCase(name)) {
        resourceId = value;
      } else {
        super.addHeader(name, value);
      }
    }
  }

  /** Replays the bytes already read for the hash to whatever reads the body next. */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override public ServletInputStream getInputStream() {
      ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override public int read() {
          return byteArrayInputStream.read();
        }
        @Override public int read(byte[] b, int off, int len) {
          return byteArrayInputStream.read(b, off, len);
        }
        @Override public boolean isFinished() {
          return byteArrayInputStream.available() == 0;
        }
        @Override public boolean isReady() {
          return true;
        }
        @Override public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override public BufferedReader getReader() {
      String enc = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
      return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.Charset.forName(enc)));
    }
  }
}
