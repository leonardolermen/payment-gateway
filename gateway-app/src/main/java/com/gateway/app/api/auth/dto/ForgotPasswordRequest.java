package com.gateway.app.api.auth.dto;

/** POST /v1/auth/password/forgot. */
public record ForgotPasswordRequest(String email) {

  public void validate() {
    required(email, "email");
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
