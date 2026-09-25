package com.gateway.providers.itau.auth;

import com.gateway.kernel.provider.ProviderEnvironment;
import java.net.URI;

/**
 * Base URL, token URL and whether mTLS applies, per environment. Sandbox is a different auth model
 * (plain OAuth, no client certificate) — see docs/providers/itau/NOTES.md, "Sandbox authentication".
 */
public record ItauEndpoints(URI apiBase, URI tokenUrl, boolean mutualTls) {

  // docs/providers/itau/NOTES.md, "Base URLs" / "Authentication" (read 2026-09-24).
  private static final URI LIVE_API_BASE = URI.create("https://pix-pj.api.itau.com/regulatorio-pix/v2");
  private static final URI LIVE_TOKEN_URL = URI.create("https://sts.itau.com.br/as/token.oauth2");

  // docs/providers/itau/NOTES.md, "Sandbox authentication" (portal-hosted sandbox, no mTLS).
  private static final URI TEST_API_BASE =
      URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-api-regulatorio-pix-v2-externo/v2");
  private static final URI TEST_TOKEN_URL = URI.create("https://sandbox.devportal.itau.com.br/api/oauth/jwt");

  public static ItauEndpoints forEnvironment(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_API_BASE, LIVE_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_API_BASE, TEST_TOKEN_URL, false);
    };
  }

  public static ItauEndpoints custom(URI apiBase, URI tokenUrl, boolean mutualTls) {
    return new ItauEndpoints(apiBase, tokenUrl, mutualTls);
  }

  // Bolecode (spec 2026-09-25 §1): three products, three bases. Production issue/query authenticate at the
  // same STS path as Pix (APIGatewaySTSAuthorizer); cash_management declares its own tokenUrl in its
  // OpenAPI securitySchemes. Sandbox: all three behind the portal, same /api/oauth/jwt, no mTLS.
  private static final URI LIVE_BOLETO_ISSUE_BASE = URI.create("https://pix-pj.api.itau.com/recebimentos-pix/v1");
  private static final URI LIVE_BOLETO_QUERY_BASE = URI.create("https://secure.api.cloud.itau.com.br/boletoscash/v2");
  private static final URI LIVE_BOLETO_INSTRUCTION_BASE = URI.create("https://api.gateway.itau.com.br/cash_management/v2");
  public static final URI CASH_MANAGEMENT_TOKEN_URL = URI.create("https://sts.itau.com.br/api/oauth/token");
  private static final URI TEST_BOLETO_ISSUE_BASE = URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1");
  private static final URI TEST_BOLETO_QUERY_BASE = URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1");
  private static final URI TEST_BOLETO_INSTRUCTION_BASE = URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-cash-management-ext-v2/v2");

  public static ItauEndpoints boletoIssue(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_BOLETO_ISSUE_BASE, LIVE_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_BOLETO_ISSUE_BASE, TEST_TOKEN_URL, false);
    };
  }

  public static ItauEndpoints boletoQuery(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_BOLETO_QUERY_BASE, LIVE_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_BOLETO_QUERY_BASE, TEST_TOKEN_URL, false);
    };
  }

  public static ItauEndpoints boletoInstruction(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_BOLETO_INSTRUCTION_BASE, CASH_MANAGEMENT_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_BOLETO_INSTRUCTION_BASE, TEST_TOKEN_URL, false);
    };
  }
}
