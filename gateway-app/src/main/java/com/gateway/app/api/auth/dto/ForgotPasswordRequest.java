package com.gateway.app.api.auth.dto;

import com.gateway.app.api.support.Required;

/** POST /v1/auth/password/forgot. */
public record ForgotPasswordRequest(String email) {

  public void validate() {
    Required.field(email, "email");
  }
}
