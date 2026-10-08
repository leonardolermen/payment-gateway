package com.gateway.app.api.auth.dto;

/** POST /v1/auth/invite/accept: e-mail and role come from the invite, not from the body. */
public record AcceptInviteRequest(String token, String name, String password) {

  public void validate() {
    Required.field(token, "token");
    Required.field(name, "name");
    Required.field(password, "password");
  }

  @Override
  public String toString() {
    return "AcceptInviteRequest[***]";
  }
}
