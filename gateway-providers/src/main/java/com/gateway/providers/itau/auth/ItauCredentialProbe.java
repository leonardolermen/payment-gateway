package com.gateway.providers.itau.auth;

import com.gateway.kernel.provider.CredentialProbe;
import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.ProbePhrases;
import java.security.KeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Test connection" for the Itaú: one token request at the STS and nothing else, so a probe can
 * never create a charge. The endpoints are the configured ones (the same the Pix provider uses),
 * not the bank's defaults, so a test points where the payments will go.
 */
public final class ItauCredentialProbe implements CredentialProbe {
  private static final Logger LOG = LoggerFactory.getLogger(ItauCredentialProbe.class);

  private final ItauTokenClient tokens;
  private final KeyStore trustStore;
  private final ItauEndpoints live;
  private final ItauEndpoints test;

  public ItauCredentialProbe(
      ItauTokenClient tokens, KeyStore trustStore, ItauEndpoints live, ItauEndpoints test) {
    this.tokens = tokens;
    this.trustStore = trustStore;
    this.live = live;
    this.test = test;
  }

  @Override
  public String providerId() {
    return "ITAU";
  }

  @Override
  public ProbeResult probe(ProviderCredentials credentials) {
    ItauCredentials parsed;
    try {
      parsed = parse(credentials);
    } catch (IllegalArgumentException e) {
      return ProbePhrases.incomplete(e);
    }

    ItauEndpoints endpoints = endpointsFor(credentials.environment());
    if (endpoints.mutualTls() && !pemMaterialBuilds(parsed)) {
      return ProbePhrases.INVALID_CERTIFICATE;
    }

    // The token client caches a token per credential for 300 s. A test is the merchant asking the
    // bank now — a cached "yes" from before a secret was rotated at the bank would lie — so the
    // entry goes first. The payments flow rebuilds it on its next call, which is the usual cost.
    tokens.evict(parsed.fingerprint());

    try {
      tokens.tokenFor(parsed, endpoints, trustStore);
      return ProbePhrases.CONNECTED;
    } catch (ProviderException e) {
      return ProbePhrases.from(e);
    } catch (RuntimeException e) {
      // A malformed 200 body, a TLS context that failed past the PEM check: a verdict, never a
      // 500. The class name is enough to investigate and carries nothing of the credential.
      LOG.warn("itau credential probe failed unexpectedly: {}", e.getClass().getName());
      return ProbePhrases.UNEXPECTED;
    }
  }

  /** On LIVE the production shape is required before any network: fail on the field, not on TLS. */
  private static ItauCredentials parse(ProviderCredentials credentials) {
    ItauCredentials parsed = ItauCredentials.parse(credentials.payload());

    if (credentials.environment() == ProviderEnvironment.LIVE) {
      parsed.requireProductionShape();
    }

    return parsed;
  }

  /**
   * The key store is built here first, on its own, so that an {@code IllegalArgumentException}
   * means the PEM material and nothing else; the token client builds it again, which is the price
   * of not reporting an unrelated failure as a certificate problem.
   */
  private boolean pemMaterialBuilds(ItauCredentials parsed) {
    try {
      PemKeyStores.mutualTls(parsed.certificatePem(), parsed.privateKeyPem().reveal(), trustStore);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private ItauEndpoints endpointsFor(ProviderEnvironment environment) {
    return environment == ProviderEnvironment.LIVE ? live : test;
  }
}
