package com.gateway.app.api.auth;

import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.usertoken.UserToken;
import com.gateway.merchants.usertoken.UserTokenService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consume, register and verify in one transaction: a failure after consume rolls the invite back to
 * usable, and a user never exists unverified because of a crash between two steps.
 */
@Component
public class InviteAcceptService {
  private final UserTokenService tokens;
  private final UserService users;

  public InviteAcceptService(UserTokenService tokens, UserService users) {
    this.tokens = tokens;
    this.users = users;
  }

  @Transactional
  public User accept(String plainToken, String name, String password) {
    // Peek and check first: the cheap refusals answer without touching the token.
    UserToken invite = tokens.peek(UserToken.Kind.INVITE, plainToken).orElseThrow(this::linkGone);
    EmailAddress email = new EmailAddress(invite.payload().get("email"));
    Role role = Role.valueOf(invite.payload().get("role"));

    users.requireStrongPassword(password);
    if (users.findActiveByEmail(email).isPresent()) {
      throw new DomainException("EMAIL_TAKEN", "email is already in use");
    }

    UserToken token = tokens.consume(UserToken.Kind.INVITE, plainToken).orElseThrow(this::linkGone);

    User user = users.register(token.merchantId(), name, email, role, password);
    // The invite reached this inbox: that is the verification.
    return users.markEmailVerified(user.id());
  }

  private DomainException linkGone() {
    return new DomainException("TOKEN_EXPIRED", "this link is no longer valid");
  }
}
