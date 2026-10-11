package com.gateway.app.api.auth.dto;

import com.gateway.app.api.support.Required;

/** POST /v1/auth/email/verify: the token from the e-mailed link. */
public record VerifyEmailRequest(String token) {

  public void validate() {
    Required.field(token, "token");
  }

  @Override
  public String toString() {
    return "VerifyEmailRequest[***]";
  }
}
