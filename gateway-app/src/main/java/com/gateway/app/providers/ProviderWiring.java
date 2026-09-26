package com.gateway.app.providers;

import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.payments.PaymentsConfiguration;
import com.gateway.providers.ProvidersConfiguration;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Where the three modules meet: payments asks for a {@link CredentialLookup} and a {@code
 * PixProvider} through kernel interfaces, providers supplies the bank, merchants holds the
 * encrypted credentials. Only the app may know all three (ArchUnit {@code
 * businessModulesDoNotImportEachOther}), so the adapter between merchants' vocabulary ({@link
 * Provider}, {@link ApiKeyEnvironment}) and the kernel's ({@code String}, {@link
 * ProviderEnvironment}) lives here.
 */
@Configuration(proxyBeanMethods = false)
@Import({ProvidersConfiguration.class, PaymentsConfiguration.class})
public class ProviderWiring {

  /**
   * An unknown provider name is "no credential" rather than an exception: {@code ProviderGateway}
   * turns an empty result into {@code PROVIDER_CREDENTIALS_MISSING}, which is the answer a merchant
   * can act on; an {@code IllegalArgumentException} from {@code valueOf} would surface as a 400
   * about our own internal name.
   */
  @Bean
  CredentialLookup credentialLookup(ProviderCredentialService credentials) {
    return (merchantId, provider, env) -> {
      Provider p;
      try {
        p = Provider.valueOf(provider);
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
      ApiKeyEnvironment keyEnv =
          env == ProviderEnvironment.LIVE ? ApiKeyEnvironment.LIVE : ApiKeyEnvironment.TEST;
      return credentials
          .decrypt(merchantId, p, keyEnv)
          .map(bytes -> new ProviderCredentials(bytes, env));
    };
  }
}
