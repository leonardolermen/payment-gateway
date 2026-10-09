package com.gateway.kernel.provider;

/** Asks the bank whether a credential authenticates, without issuing or changing anything. */
public interface CredentialProbe {
  String providerId();

  ProbeResult probe(ProviderCredentials credentials);
}
