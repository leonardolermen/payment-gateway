package com.gateway.app.api.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider-credential audit trail, on the same logger as {@code AuthEvents} (spec
 * 2026-10-09-provedores-self-service, section 4): one INFO line per event, ids only. Never the
 * payload, its fingerprint, which secrets changed or the notification key.
 */
public final class ProviderEvents {
  private static final Logger log = LoggerFactory.getLogger("gateway.audit.account");

  private ProviderEvents() {}

  public static void credentialsSet(String merchantId, String provider, String environment) {
    log.info(
        "provider.credentials.set provider={} env={} merchant={}",
        provider,
        environment,
        merchantId);
  }

  /** Only the verdict travels: the phrase is on the credential and the bank body nowhere. */
  public static void tested(String merchantId, String provider, String environment, boolean ok) {
    log.info(
        "provider.test provider={} env={} ok={} merchant={}",
        provider,
        environment,
        ok,
        merchantId);
  }

  public static void notificationKeySet(String merchantId, String provider) {
    log.info("provider.notification_key.set provider={} merchant={}", provider, merchantId);
  }
}
