package com.gateway.app.api.team;

import com.gateway.app.api.auth.AuthMailService;
import com.gateway.app.security.Actor;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.usertoken.UserTokenService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The team writes that touch more than one service and must commit or fail together. */
@Component
public class TeamService {
  private final UserService users;
  private final MerchantService merchants;
  private final SessionService sessions;
  private final UserTokenService tokens;
  private final AuthMailService mail;

  public TeamService(
      UserService users,
      MerchantService merchants,
      SessionService sessions,
      UserTokenService tokens,
      AuthMailService mail) {
    this.users = users;
    this.merchants = merchants;
    this.sessions = sessions;
    this.tokens = tokens;
    this.mail = mail;
  }

  /**
   * A resend replaces the earlier invite to the same address: only the newest link works. The
   * inviter must have confirmed their own e-mail: otherwise anyone could sign up with a throwaway
   * address and use the gateway's SMTP reputation to mail invites to strangers.
   */
  @Transactional
  public void invite(MerchantId merchantId, Actor.User inviter, EmailAddress email, Role role) {
    if (!inviter.emailVerified()) {
      throw new DomainException("EMAIL_NOT_VERIFIED", "confirm your e-mail before inviting");
    }

    if (users.findActiveByEmail(email).isPresent()) {
      throw new DomainException("EMAIL_TAKEN", "email is already in use");
    }

    tokens.invalidateOpenInvites(merchantId, email.normalized());

    mail.sendInvite(merchants.get(merchantId), email, role);
  }

  /** A removed user's open sessions end with the removal, not at their next refresh. */
  @Transactional
  public void remove(MerchantId merchantId, String userId) {
    users.remove(merchantId, userId);

    sessions.revokeAll(userId);
  }
}
