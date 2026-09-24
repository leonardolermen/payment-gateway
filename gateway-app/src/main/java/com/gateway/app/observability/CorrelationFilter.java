package com.gateway.app.observability;

import com.gateway.kernel.ids.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every other filter (@Order(1), ahead of {@code AdminKeyFilter}'s 10) so a
 * correlation id exists for the whole request, including the 401s and 403s the security filters
 * produce — {@code webhook-delivery} and {@code MerchantEvents} both key off {@code MDC}'s
 * {@code correlationId} (see {@code webhook-delivery.correlation-mdc-key} in application.yml), so
 * it has to be set before anything downstream logs.
 */
@Component
@Order(1)
public class CorrelationFilter extends OncePerRequestFilter {
  private static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "correlationId";
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = req.getHeader(HEADER);
    // Echoed in a response header and written to every log line: an unbounded or arbitrary value
    // is log injection and header bloat on the caller's say-so.
    if (correlationId == null || !VALID.matcher(correlationId).matches()) {
      correlationId = Ulid.next();
    }
    res.setHeader(HEADER, correlationId);
    MDC.put(MDC_KEY, correlationId);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
