package com.gateway.app.api.provider;

import com.gateway.kernel.errors.FieldDomainException;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.itau.auth.ItauCredentials;

/**
 * Checks a merged credential with the provider's own parser before it is stored, so a merchant
 * learns of a bad field when saving and not at the first payment. The parsers' messages start with
 * the field name and never echo the value, so they go to the client as they are.
 */
final class CredentialShape {
  private CredentialShape() {}

  static void validate(Provider provider, ProviderEnvironment environment, byte[] json) {
    ProviderCatalog.of(provider);

    try {
      parse(provider, environment, json);
    } catch (IllegalArgumentException e) {
      throw new FieldDomainException(
          "PROVIDER_CREDENTIALS_INVALID", e.getMessage(), fieldOf(e.getMessage()));
    }
  }

  // The one dispatch on Provider; ProviderCatalog.of above already refused the unlisted ones.
  private static void parse(Provider provider, ProviderEnvironment environment, byte[] json) {
    switch (provider) {
      case ITAU -> {
        ItauCredentials credentials = ItauCredentials.parse(json);

        if (environment == ProviderEnvironment.LIVE) {
          credentials.requireProductionShape();
        }
      }
      case CIELO -> CieloCredentials.parse(json);
      case FAKE -> throw new IllegalStateException("FAKE is not in the catalog");
    }
  }

  private static String fieldOf(String message) {
    return message.split("\\s+", 2)[0];
  }
}
