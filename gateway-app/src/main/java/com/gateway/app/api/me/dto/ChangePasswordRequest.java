package com.gateway.app.api.me.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.app.api.support.Required;

/** POST /v1/me/password: {@code new} on the wire, a Java keyword here. */
public record ChangePasswordRequest(String current, @JsonProperty("new") String newPassword) {

  public void validate() {
    Required.field(current, "current");
    Required.field(newPassword, "new");
  }

  @Override
  public String toString() {
    return "ChangePasswordRequest[***]";
  }
}
