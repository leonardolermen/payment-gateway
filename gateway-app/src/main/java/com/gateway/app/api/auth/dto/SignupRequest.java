package com.gateway.app.api.auth.dto;

import com.gateway.app.api.support.Required;

/** POST /v1/auth/signup: a new store and its first owner. */
public record SignupRequest(String storeName, String name, String email, String password) {

  public void validate() {
    Required.field(storeName, "store_name");
    Required.field(name, "name");
    Required.field(email, "email");
    Required.field(password, "password");
  }

  @Override
  public String toString() {
    return "SignupRequest[***]";
  }
}
