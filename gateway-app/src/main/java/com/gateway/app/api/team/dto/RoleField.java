package com.gateway.app.api.team.dto;

import com.gateway.app.api.support.Required;
import com.gateway.merchants.user.Role;
import java.util.Arrays;

/** The {@code role} of a team body: a 400 that lists the roles instead of an enum stack trace. */
final class RoleField {
  private RoleField() {}

  static Role parse(String value) {
    Required.field(value, "role");

    return Arrays.stream(Role.values())
        .filter(role -> role.name().equals(value.trim()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("role must be one of OWNER, FINANCE, READONLY"));
  }
}
