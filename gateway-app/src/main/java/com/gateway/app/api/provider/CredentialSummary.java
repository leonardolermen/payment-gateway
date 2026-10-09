package com.gateway.app.api.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a credential payload may say about itself after it is encrypted: which secret fields have a
 * value, and the non-secret fields in the clear. Computed once at store time and kept beside the
 * blob, so the panel's GET never decrypts.
 *
 * @param secretsSet one entry per secret field of the provider, true when a non-blank value is set
 * @param publicFields every scalar field that is not a secret, as text; nested values are left out
 */
public record CredentialSummary(Map<String, Boolean> secretsSet, Map<String, String> publicFields) {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static CredentialSummary of(byte[] json, Set<String> secretFields) {
    JsonNode credential = JSON.readTree(json);
    Map<String, Boolean> secretsSet = new LinkedHashMap<>();
    Map<String, String> publicFields = new LinkedHashMap<>();

    for (String field : secretFields) {
      JsonNode value = credential.get(field);
      boolean isSet = value != null && value.isString() && !value.stringValue().isBlank();
      secretsSet.put(field, isSet);
    }

    for (Map.Entry<String, JsonNode> property : credential.properties()) {
      JsonNode value = property.getValue();

      if (secretFields.contains(property.getKey()) || !value.isValueNode() || value.isNull()) {
        continue;
      }

      publicFields.put(
          property.getKey(), value.isString() ? value.stringValue() : value.toString());
    }

    return new CredentialSummary(Map.copyOf(secretsSet), Map.copyOf(publicFields));
  }
}
