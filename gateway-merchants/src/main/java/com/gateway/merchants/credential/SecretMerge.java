package com.gateway.merchants.credential;

import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Merges a credential the merchant submitted with the one already stored. Secrets are never sent
 * back to the panel, so a form saved without touching them submits them absent: absent means "keep
 * what is stored", {@code ""} means "remove it", anything else replaces it. Non-secret fields are
 * always shown, so the submitted payload is the whole truth for them.
 */
public final class SecretMerge {
  private static final ObjectMapper JSON = new ObjectMapper();

  private SecretMerge() {}

  public static byte[] merge(byte[] submitted, Optional<byte[]> stored, Set<String> secretFields) {
    ObjectNode merged = object(submitted);
    Optional<ObjectNode> previous = stored.map(SecretMerge::object);

    for (String field : secretFields) {
      JsonNode value = merged.get(field);

      if (value == null) {
        previous
            .map(storedObject -> storedObject.get(field))
            .ifPresent(storedValue -> merged.set(field, storedValue));
      } else if (value.isString() && value.asString().isEmpty()) {
        merged.remove(field);
      }
    }

    return JSON.writeValueAsBytes(merged);
  }

  private static ObjectNode object(byte[] json) {
    try {
      if (JSON.readTree(json) instanceof ObjectNode objectNode) {
        return objectNode;
      }
    } catch (JacksonException e) {
      // The parser's text quotes a slice of the payload, which may be a secret.
    }

    throw new IllegalArgumentException("payload must be a JSON object");
  }
}
