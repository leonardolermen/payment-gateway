package com.gateway.app.api.team.dto;

import com.gateway.merchants.usertoken.UserToken;
import java.time.Instant;

/** An invite not accepted yet; the token itself never leaves the e-mail. */
public record PendingInviteResponse(String email, String role, Instant expiresAt) {

  public static PendingInviteResponse of(UserToken invite) {
    return new PendingInviteResponse(
        invite.payload().get("email"), invite.payload().get("role"), invite.expiresAt());
  }
}
