package com.gateway.app.api.auth.dto;

/** POST /v1/auth/email/verify: the token from the e-mailed link. */
public record VerifyEmailRequest(String token) {

  public void validate() {
    required(token, "token");
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  @Override
  public String toString() {
    return "VerifyEmailRequest[***]";
  }
}
