package com.gateway.app.api.auth.dto;

/** POST /v1/auth/password/reset: the token from the e-mailed link and the new password. */
public record ResetPasswordRequest(String token, String password) {

  public void validate() {
    required(token, "token");
    required(password, "password");
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  @Override
  public String toString() {
    return "ResetPasswordRequest[***]";
  }
}
