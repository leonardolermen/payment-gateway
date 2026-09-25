package com.gateway.app.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * The path the filters classify on must be the path Spring MVC routes on. Raw
 * {@code getRequestURI()} is not: MVC decodes percent-encoding and drops {@code ;matrix} content, so
 * {@code /v1/%61dmin/merchants} and {@code /v1/admin;x/merchants} reached the admin controller while
 * {@code AdminKeyFilter} skipped them and {@code ApiKeyAuthFilter} accepted a merchant key.
 */
public record RequestPath(String raw, String normalized) {
  private static final UrlPathHelper HELPER = new UrlPathHelper();
  static {
    HELPER.setRemoveSemicolonContent(true);
    HELPER.setUrlDecode(true);
  }

  public static RequestPath of(HttpServletRequest req) {
    String context = req.getContextPath() == null ? "" : req.getContextPath();
    String uri = req.getRequestURI();
    String raw = uri.startsWith(context) ? uri.substring(context.length()) : uri;
    return new RequestPath(raw, HELPER.getPathWithinApplication(req));
  }

  /** Anything that makes the routed path differ from what a plain prefix check sees. */
  public boolean suspicious() {
    if (raw.indexOf(';') >= 0 || raw.indexOf('%') >= 0 || raw.indexOf('\\') >= 0) {
      return true;
    }
    for (String segment : raw.split("/", -1)) if (segment.equals("..") || segment.equals(".")) return true;
    return !raw.equals(normalized);
  }
}
