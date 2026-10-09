package com.gateway.app.api.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider-credential audit trail, in the style of {@code AuthEvents}: one INFO line per event,
 * ids only. Never the payload, its fingerprint or which secrets changed.
 */
public final class ProviderEvents {
  private static final Logger log = LoggerFactory.getLogger("gateway.audit.provider");

  private ProviderEvents() {}

  public static void credentialsSet(String merchantId, String provider, String environment) {
    log.info(
        "provider.credentials.set provider={} env={} merchant={}",
        provider,
        environment,
        merchantId);
  }
}
