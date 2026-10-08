package com.gateway.app.api.support;

/**
 * The one required-field check of the request bodies; the 400 names the field as the client sent
 * it.
 */
public final class Required {
  private Required() {}

  public static void field(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
