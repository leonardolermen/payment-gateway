package com.gateway.app.api.auth.dto;

/**
 * The one required-field check of the auth bodies; the 400 names the field as the client sent it.
 */
final class Required {
  private Required() {}

  static void field(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
