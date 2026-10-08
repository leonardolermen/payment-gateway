package com.gateway.app.api.auth.dto;

import com.gateway.app.api.support.Required;

/** POST /v1/auth/password/reset: the token from the e-mailed link and the new password. */
public record ResetPasswordRequest(String token, String password) {

  public void validate() {
    Required.field(token, "token");
    Required.field(password, "password");
  }

  @Override
  public String toString() {
    return "ResetPasswordRequest[***]";
  }
}
