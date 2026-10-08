package com.gateway.app.api.team.dto;

import com.gateway.app.api.support.Required;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;

/** POST /v1/invites. */
public record InviteRequest(String email, String role) {

  public void validate() {
    Required.field(email, "email");
    RoleField.parse(role);
  }

  public EmailAddress emailAddress() {
    return new EmailAddress(email);
  }

  public Role parsedRole() {
    return RoleField.parse(role);
  }
}
