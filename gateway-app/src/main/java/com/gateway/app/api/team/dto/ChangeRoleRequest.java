package com.gateway.app.api.team.dto;

import com.gateway.merchants.user.Role;

/** PATCH /v1/merchant/users/{id}. */
public record ChangeRoleRequest(String role) {

  public void validate() {
    RoleField.parse(role);
  }

  public Role parsedRole() {
    return RoleField.parse(role);
  }
}
