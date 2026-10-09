package com.gateway.app.api.auth;

import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.usertoken.UserToken;
import com.gateway.merchants.usertoken.UserTokenService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consume, reset and revoke in one transaction: a user removed after the e-mail went out makes the
 * reset fail, and the rollback leaves the link unspent instead of a token burned for nothing.
 */
@Component
public class PasswordResetService {
  private final UserTokenService tokens;
  private final UserService users;
  private final SessionService sessions;

  public PasswordResetService(UserTokenService tokens, UserService users, SessionService sessions) {
    this.tokens = tokens;
    this.users = users;
    this.sessions = sessions;
  }

  /** Returns the id of the user whose password changed. */
  @Transactional
  public String reset(String plainToken, String password) {
    // Checked before consume: a rejected password must not spend the one-time link.
    users.requireStrongPassword(password);

    UserToken token =
        tokens
            .consume(UserToken.Kind.RESET_PASSWORD, plainToken)
            .orElseThrow(
                () -> new DomainException("TOKEN_EXPIRED", "this link is no longer valid"));

    users.resetPassword(token.userId(), password);
    sessions.revokeAll(token.userId());

    return token.userId();
  }
}
