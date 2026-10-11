package com.gateway.providers;

import java.util.Objects;
import tools.jackson.core.JacksonException;

/**
 * The field a credential error is about. The parsers' messages start with the field name as the
 * client sends it ({@code client_secret is required}), so the first token is the field and the rest
 * of the message — which may one day quote a value — never has to travel. A Jackson failure names
 * its field through the path instead: a secret sent as {@code {}} or {@code []} cannot bind, and
 * the property on the path is the one that could not.
 */
public final class CredentialField {
  private CredentialField() {}

  /** The first token of a parser message, or null when the message carries none. */
  public static String of(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }

    return message.trim().split("\s+", 2)[0];
  }

  /** The first named property on the failure's path, or null when the path has none. */
  public static String of(JacksonException failure) {
    if (failure.getPath() == null) {
      return null;
    }

    return failure.getPath().stream()
        .map(JacksonException.Reference::getPropertyName)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }
}
