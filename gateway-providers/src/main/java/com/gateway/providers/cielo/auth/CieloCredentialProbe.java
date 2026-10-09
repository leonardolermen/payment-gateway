package com.gateway.providers.cielo.auth;

import com.gateway.kernel.provider.CredentialProbe;
import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.ProbePhrases;
import com.gateway.providers.cielo.CieloHttp;
import java.net.http.HttpResponse;

/**
 * "Test connection" for the Cielo, which has no token endpoint: one GET on the query host for a
 * PaymentId that cannot exist. The Cielo authenticates before it looks, so 404 proves the
 * credential and 401 refutes it — and a query never creates or changes a sale.
 */
public final class CieloCredentialProbe implements CredentialProbe {
  private static final String NULL_SALE = "/1/sales/00000000-0000-0000-0000-000000000000";

  private final CieloHttp http;
  private final CieloEndpoints live;
  private final CieloEndpoints test;

  public CieloCredentialProbe(CieloHttp http, CieloEndpoints live, CieloEndpoints test) {
    this.http = http;
    this.live = live;
    this.test = test;
  }

  @Override
  public String providerId() {
    return "CIELO";
  }

  @Override
  public ProbeResult probe(ProviderCredentials credentials) {
    CieloCredentials parsed;
    try {
      parsed = CieloCredentials.parse(credentials.payload());
    } catch (IllegalArgumentException e) {
      return ProbePhrases.incomplete(e);
    }

    HttpResponse<String> response;
    try {
      CieloEndpoints endpoints = endpointsFor(credentials.environment());
      response = http.send(parsed, http.request(endpoints.apiQuery(), NULL_SALE).GET());
    } catch (ProviderException e) {
      return ProbePhrases.from(e);
    }

    return switch (response.statusCode()) {
      case 404 -> ProbePhrases.CONNECTED;
      case 401, 403 -> ProbePhrases.REFUSED;
      default -> ProbePhrases.UNEXPECTED;
    };
  }

  private CieloEndpoints endpointsFor(ProviderEnvironment environment) {
    return environment == ProviderEnvironment.LIVE ? live : test;
  }
}
