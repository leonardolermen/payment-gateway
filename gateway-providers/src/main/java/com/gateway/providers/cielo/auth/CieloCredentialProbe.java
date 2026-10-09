package com.gateway.providers.cielo.auth;

import com.gateway.kernel.provider.CredentialProbe;
import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.ProbePhrases;
import com.gateway.providers.cielo.CieloHttp;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Test connection" for the Cielo, which has no token endpoint: one GET on the query host for a
 * PaymentId that cannot exist. The Cielo authenticates before it looks, so 404 proves the
 * credential and 401 refutes it — and a query never creates or changes a sale.
 */
public final class CieloCredentialProbe implements CredentialProbe {
  private static final Logger LOG = LoggerFactory.getLogger(CieloCredentialProbe.class);
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
    } catch (RuntimeException e) {
      // A verdict, never a 500; the class name says enough and carries nothing of the credential.
      LOG.warn("cielo credential probe failed unexpectedly: {}", e.getClass().getName());
      return ProbePhrases.UNEXPECTED;
    }

    return switch (response.statusCode()) {
      case 404 -> isCielosOwn(response) ? ProbePhrases.CONNECTED : ProbePhrases.UNEXPECTED;
      case 401, 403 -> ProbePhrases.REFUSED;
      default -> ProbePhrases.UNEXPECTED;
    };
  }

  /**
   * A 404 only proves the credential when it is the Cielo's: an empty body or a JSON one. A proxy
   * or a wrong base URL answers 404 too, with an HTML page, and that would read as "Conectado" for
   * a credential nobody checked.
   */
  private static boolean isCielosOwn(HttpResponse<String> response) {
    String body = response.body() == null ? "" : response.body().strip();

    return body.isEmpty() || body.startsWith("{") || body.startsWith("[");
  }

  private CieloEndpoints endpointsFor(ProviderEnvironment environment) {
    return environment == ProviderEnvironment.LIVE ? live : test;
  }
}
