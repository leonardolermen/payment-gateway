package com.gateway.app.api.team.dto;

import com.gateway.merchants.user.User;
import java.time.Instant;

public record TeamMemberResponse(
    String id, String name, String email, String role, Instant lastLoginAt) {

  public static TeamMemberResponse of(User user) {
    return new TeamMemberResponse(
        user.id(), user.name(), user.email().value(), user.role().name(), user.lastLoginAt());
  }
}
