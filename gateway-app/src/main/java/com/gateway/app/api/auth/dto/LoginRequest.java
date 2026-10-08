package com.gateway.app.api.auth.dto;

/** POST /v1/auth/login. */
public record LoginRequest(String email, String password) {

  public void validate() {
    required(email, "email");
    required(password, "password");
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  @Override
  public String toString() {
    return "LoginRequest[***]";
  }
}
