package com.gateway.app.api.provider;

import com.gateway.kernel.errors.FieldDomainException;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.providers.CredentialField;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.itau.auth.ItauCredentials;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks a merged credential with the provider's own parser before it is stored, so a merchant
 * learns of a bad field when saving and not at the first payment. The parsers' messages start with
 * the field name and never echo the value, so they go to the client as they are.
 */
final class CredentialShape {
  private static final ObjectMapper JSON = new ObjectMapper();

  private CredentialShape() {}

  static void validate(Provider provider, ProviderEnvironment environment, byte[] json) {
    ProviderCatalog.Entry entry = ProviderCatalog.of(provider);

    refuseUnknownKeys(provider, entry, json);

    try {
      parse(provider, environment, json);
    } catch (IllegalArgumentException e) {
      throw new FieldDomainException(
          "PROVIDER_CREDENTIALS_INVALID", e.getMessage(), CredentialField.of(e.getMessage()));
    } catch (JacksonException e) {
      // A secret sent as {} or []: the parsers' Raw record cannot bind it. Jackson's own message
      // quotes a slice of the body, so a fixed detail goes out and only the field name travels.
      throw new FieldDomainException(
          "PROVIDER_CREDENTIALS_INVALID",
          "payload has a field of the wrong type",
          CredentialField.of(e));
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

  /**
   * Before the parsers: they ignore what they do not know, so {@code clientSecret} or {@code
   * "client_secret "} would sail through as a public field and be stored — and shown — in the
   * clear. The key goes back verbatim (spaces included) so the client sees what it sent.
   */
  private static void refuseUnknownKeys(
      Provider provider, ProviderCatalog.Entry entry, byte[] json) {
    JsonNode payload;
    try {
      payload = JSON.readTree(json);
    } catch (JacksonException e) {
      throw new FieldDomainException(
          "PROVIDER_CREDENTIALS_INVALID", "payload is not a JSON object", null);
    }

    for (String key : payload.propertyNames()) {
      if (!entry.accepts(key)) {
        throw new FieldDomainException(
            "PROVIDER_CREDENTIALS_INVALID", key + " is not a field of " + provider, key);
      }
    }
  }
}
