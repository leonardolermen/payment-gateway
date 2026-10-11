package com.gateway.app.api.auth.dto;

import com.gateway.app.api.support.Required;

/** POST /v1/auth/login. */
public record LoginRequest(String email, String password) {

  public void validate() {
    Required.field(email, "email");
    Required.field(password, "password");
  }

  @Override
  public String toString() {
    return "LoginRequest[***]";
  }
}
