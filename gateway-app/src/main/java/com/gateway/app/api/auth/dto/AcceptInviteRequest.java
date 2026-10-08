package com.gateway.app.api.auth.dto;

/** POST /v1/auth/invite/accept: e-mail and role come from the invite, not from the body. */
public record AcceptInviteRequest(String token, String name, String password) {

  public void validate() {
    required(token, "token");
    required(name, "name");
    required(password, "password");
  }

  private static void required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  @Override
  public String toString() {
    return "AcceptInviteRequest[***]";
  }
}
