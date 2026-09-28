package com.gateway.providers.cielo.auth;

import com.gateway.kernel.provider.ProviderEnvironment;
import java.net.URI;

/**
 * Two hosts per environment: transactional (authorize, capture, void, tokenize) and query (GET).
 * reference/como-usar-o-sandbox for the sandbox pair; each reference page's "Produção" row for the
 * production pair (read 2026-09-28).
 */
public record CieloEndpoints(URI api, URI apiQuery) {
  private static final URI LIVE_API = URI.create("https://api.cieloecommerce.cielo.com.br");
  private static final URI LIVE_QUERY = URI.create("https://apiquery.cieloecommerce.cielo.com.br");
  private static final URI TEST_API = URI.create("https://apisandbox.cieloecommerce.cielo.com.br");
  private static final URI TEST_QUERY =
      URI.create("https://apiquerysandbox.cieloecommerce.cielo.com.br");

  public static CieloEndpoints forEnvironment(ProviderEnvironment environment) {
    return switch (environment) {
      case LIVE -> new CieloEndpoints(LIVE_API, LIVE_QUERY);
      case TEST -> new CieloEndpoints(TEST_API, TEST_QUERY);
    };
  }
}
