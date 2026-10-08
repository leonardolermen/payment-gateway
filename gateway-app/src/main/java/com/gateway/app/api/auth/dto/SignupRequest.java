package com.gateway.app.api.auth.dto;

/** POST /v1/auth/signup: a new store and its first owner. */
public record SignupRequest(String storeName, String name, String email, String password) {

  public void validate() {
    required(storeName, "store_name");
    required(name, "name");
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
    return "SignupRequest[***]";
  }
}
