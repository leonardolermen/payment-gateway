package com.gateway.app.api.provider;

import com.gateway.kernel.provider.CredentialProbe;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One probe per provider id, indexed once at construction: two for the same bank fail the startup
 * instead of leaving "test connection" to whichever bean came first.
 */
public class CredentialProbes {
  private final Map<String, CredentialProbe> byProviderId;

  public CredentialProbes(List<CredentialProbe> probes) {
    Map<String, CredentialProbe> indexed = new HashMap<>();

    for (CredentialProbe probe : probes) {
      CredentialProbe previous = indexed.put(probe.providerId(), probe);
      if (previous != null) {
        throw new IllegalStateException("two credential probes for " + probe.providerId());
      }
    }

    this.byProviderId = Map.copyOf(indexed);
  }

  public CredentialProbe forProvider(String providerId) {
    CredentialProbe probe = byProviderId.get(providerId);
    if (probe == null) {
      throw new IllegalArgumentException("no credential probe for " + providerId);
    }

    return probe;
  }
}
