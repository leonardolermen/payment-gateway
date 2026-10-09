package com.gateway.app.api.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a credential payload may say about itself after it is encrypted: which secret fields have a
 * value, and the non-secret fields in the clear. Computed once at store time and kept beside the
 * blob, so the panel's GET never decrypts.
 *
 * @param secretsSet one entry per secret field of the provider, true when a non-blank value is set
 * @param publicFields the provider's public fields that carry a scalar value, as text
 */
public record CredentialSummary(Map<String, Boolean> secretsSet, Map<String, String> publicFields) {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** For a credential outside the catalog (the admin seeding FAKE): nothing is summarised. */
  public static CredentialSummary none() {
    return new CredentialSummary(Map.of(), Map.of());
  }

  /**
   * Only the entry's public fields are copied — an allow-list, not "everything that is not a
   * secret": {@code CredentialShape} already refuses unknown keys, and this is the second lock
   * should a payload ever reach here without passing through it.
   */
  public static CredentialSummary of(byte[] json, ProviderCatalog.Entry entry) {
    JsonNode credential = JSON.readTree(json);
    Map<String, Boolean> secretsSet = new LinkedHashMap<>();
    Map<String, String> publicFields = new LinkedHashMap<>();

    for (String field : entry.secretFields()) {
      JsonNode value = credential.get(field);
      boolean isSet = value != null && value.isString() && !value.stringValue().isBlank();
      secretsSet.put(field, isSet);
    }

    for (String field : entry.publicFields()) {
      JsonNode value = credential.get(field);

      if (value == null || !value.isValueNode() || value.isNull()) {
        continue;
      }

      publicFields.put(field, value.isString() ? value.stringValue() : value.toString());
    }

    return new CredentialSummary(Map.copyOf(secretsSet), Map.copyOf(publicFields));
  }
}
