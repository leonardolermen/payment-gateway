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
}
