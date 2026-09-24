package com.gateway.app.mtls;

import com.gateway.app.security.Problems;
import com.gateway.app.security.RequestPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Both connectors share one servlet context, so without this the webhook route would answer on the
 * plain port (with no client certificate at all, since ProtectedRoutes exempts it from API keys) and
 * the whole API would answer on the mTLS port. Closed in both directions: provider paths anywhere but
 * the mTLS port are 404 (indistinguishable from a route that does not exist), anything else on the
 * mTLS port is 403. Runs before PathSanityFilter (0) and classifies on the normalized path the
 * dispatcher routes on, so an encoded variant cannot slip a provider path past it.
 */
@Component
@Order(-10)
public class MtlsPortFilter extends OncePerRequestFilter {
  private final WebhookMtlsProperties props;

  public MtlsPortFilter(WebhookMtlsProperties props) { this.props = props; }

  static boolean isProviderPath(String path) { return path.equals("/v1/providers") || path.startsWith("/v1/providers/"); }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    boolean onMtlsPort = props.enabled() && req.getLocalPort() == props.port();
    boolean providerPath = isProviderPath(RequestPath.of(req).normalized());
    if (providerPath && !onMtlsPort) {
      Problems.write(res, 404, "NOT_FOUND", "not found");
      return;
    }
    if (!providerPath && onMtlsPort) {
      Problems.write(res, 403, "FORBIDDEN", "this port only serves provider webhooks");
      return;
    }
    chain.doFilter(req, res);
  }
}
