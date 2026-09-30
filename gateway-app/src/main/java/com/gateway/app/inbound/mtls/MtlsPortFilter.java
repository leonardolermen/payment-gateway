package com.gateway.app.inbound.mtls;

import com.gateway.app.security.Problems;
import com.gateway.app.security.RequestPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Both connectors share one servlet context, so without this the webhook route would answer on the
 * plain port (with no client certificate at all, since ProtectedRoutes exempts it from API keys)
 * and the whole API would answer on the mTLS port. Closed in both directions: the Itaú's provider
 * paths anywhere but the mTLS port are 404 (indistinguishable from a route that does not exist; the
 * Cielo's live on the main connector: it offers no client certificate and delivers only on 443),
 * anything else on the mTLS port is 403. Runs before PathSanityFilter (0) and classifies on the
 * normalized path the dispatcher routes on, so an encoded variant cannot slip a provider path past
 * it.
 *
 * <p>Two more checks on the mTLS port, both before any body is read: a declared Content-Length over
 * {@code max-body-bytes} is 413 (the controller caps chunked bodies itself), and when {@code
 * allowed-subjects} is set the client certificate's subject must be one of them (403). The CA check
 * alone admits every certificate that CA ever signed; whether the bank's CA signs other customers'
 * certificates is unverified, so the allow-list is how to narrow it once the subject is known.
 */
@Component
@Order(-10)
public class MtlsPortFilter extends OncePerRequestFilter {
  private final WebhookMtlsProperties properties;

  private final List<X500Principal> allowedSubjects;

  public MtlsPortFilter(WebhookMtlsProperties properties) {
    this.properties = properties;
    // X500Principal equality compares the canonical form, so "CN=a, O=b" and "cn=a,o=b" match.
    this.allowedSubjects = properties.allowedSubjects().stream().map(X500Principal::new).toList();
  }

  /**
   * Error dispatches on the mTLS port are fenced too, or an error page could render an API route
   * there.
   */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  /** Only the bank that authenticates with a client certificate is fenced onto the mTLS port. */
  static boolean isProviderPath(String path) {
    return path.equals("/v1/providers/itau") || path.startsWith("/v1/providers/itau/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean onMtlsPort = properties.enabled() && request.getLocalPort() == properties.port();
    boolean providerPath = isProviderPath(RequestPath.of(request).normalized());
    if (providerPath && !onMtlsPort) {
      Problems.write(response, 404, "NOT_FOUND", "not found");
      return;
    }
    if (!providerPath && onMtlsPort) {
      Problems.write(response, 403, "FORBIDDEN", "this port only serves provider webhooks");
      return;
    }
    if (onMtlsPort) {
      if (request.getContentLengthLong() > properties.maxBodyBytes()) {
        Problems.write(
            response,
            413,
            "PAYLOAD_TOO_LARGE",
            "webhook body exceeds " + properties.maxBodyBytes() + " bytes");
        return;
      }
      if (!allowedSubjects.isEmpty() && !subjectAllowed(request)) {
        Problems.write(response, 403, "FORBIDDEN", "client certificate not allowed");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private boolean subjectAllowed(HttpServletRequest request) {
    return request.getAttribute("jakarta.servlet.request.X509Certificate")
            instanceof X509Certificate[] chain
        && chain.length > 0
        && allowedSubjects.contains(chain[0].getSubjectX500Principal());
  }
}
