package com.gateway.app.api.provider;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredential;
import com.gateway.merchants.credential.ProviderCredentialService;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * "Test connection": decrypt the stored credential, ask the bank through its probe, keep the
 * outcome on the credential row, audit. Not {@code @Transactional} on purpose, like the payment
 * flows: the bank may take the whole read timeout to answer and a connection must not wait on it.
 * {@code recordTest} opens its own short transaction afterwards. The plaintext lives in this
 * method's frame only — never in a log, an exception or the response.
 */
@Service
public class ProviderProbeService {
  private final ProviderCredentialService credentials;
  private final CredentialProbes probes;
  private final Clock clock;

  public ProviderProbeService(
      ProviderCredentialService credentials, CredentialProbes probes, Clock clock) {
    this.credentials = credentials;
    this.probes = probes;
    this.clock = clock;
  }

  /**
   * An inactive credential counts as missing: {@code decrypt} filters it out, and the 422 is the
   * same code a payment without a credential gets, so the panel keys on one code.
   */
  public ProviderCredential.ProbeOutcome test(
      MerchantId merchantId, Provider provider, ApiKeyEnvironment environment) {
    ProviderCatalog.of(provider);

    byte[] plaintext =
        credentials
            .decrypt(merchantId, provider, environment)
            .orElseThrow(
                () ->
                    new DomainException(
                        "PROVIDER_CREDENTIALS_MISSING",
                        "no credential stored for " + provider + " in " + environment));

    ProbeResult result =
        probes
            .forProvider(provider.name())
            .probe(new ProviderCredentials(plaintext, toProviderEnvironment(environment)));

    ProviderCredential.ProbeOutcome outcome =
        new ProviderCredential.ProbeOutcome(result.ok(), result.detail(), clock.instant());
    credentials.recordTest(merchantId, provider, environment, outcome);

    ProviderEvents.tested(merchantId.value(), provider.name(), environment.name(), outcome.ok());

    return outcome;
  }

  private static ProviderEnvironment toProviderEnvironment(ApiKeyEnvironment environment) {
    return environment == ApiKeyEnvironment.LIVE
        ? ProviderEnvironment.LIVE
        : ProviderEnvironment.TEST;
  }
}
