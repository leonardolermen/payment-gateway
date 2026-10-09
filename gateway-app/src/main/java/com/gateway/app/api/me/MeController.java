package com.gateway.app.api.me;

import com.gateway.app.api.auth.AuthEvents;
import com.gateway.app.api.auth.AuthMailService;
import com.gateway.app.api.me.dto.ChangePasswordRequest;
import com.gateway.app.api.me.dto.MeResponse;
import com.gateway.app.api.me.dto.RenameRequest;
import com.gateway.app.api.me.dto.SessionSummaryResponse;
import com.gateway.app.security.Actor;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.usertoken.UserToken;
import com.gateway.merchants.usertoken.UserTokenService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own account. Only a user session reaches here: the session filter answers a
 * key with 403 USER_SESSION_REQUIRED, and lets every role manage itself.
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {
  private static final Duration RESEND_EVERY = Duration.ofMinutes(5);

  private final UserService users;
  private final MerchantService merchants;
  private final SessionService sessions;
  private final UserTokenService tokens;
  private final AuthMailService mail;
  private final Clock clock;

  public MeController(
      UserService users,
      MerchantService merchants,
      SessionService sessions,
      UserTokenService tokens,
      AuthMailService mail,
      Clock clock) {
    this.users = users;
    this.merchants = merchants;
    this.sessions = sessions;
    this.tokens = tokens;
    this.mail = mail;
    this.clock = clock;
  }

  @GetMapping
  public MeResponse me() {
    return describe(users.get(caller().userId()));
  }

  @PatchMapping
  public MeResponse rename(@RequestBody RenameRequest request) {
    request.validate();

    return describe(users.rename(caller().userId(), request.name()));
  }

  /** The other devices are signed out: a changed password is often a suspected leak. */
  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(@RequestBody ChangePasswordRequest request) {
    request.validate();
    Actor.User caller = caller();

    users.changePassword(caller.userId(), request.current(), request.newPassword());

    sessions.revokeOthers(caller.userId(), caller.sessionId());
    AuthEvents.passwordChanged(caller.userId());
    AuthEvents.sessionsRevoked(caller.userId(), "others");
  }

  @PostMapping("/email/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void resendVerification() {
    User user = users.get(caller().userId());
    if (user.isEmailVerified()) {
      throw new DomainException("ALREADY_VERIFIED", "the e-mail is already verified");
    }

    // The newest link's age, not a counter: an inbox flooded through our resend button is on us.
    boolean sentRecently =
        tokens
            .newestOpen(UserToken.Kind.VERIFY_EMAIL, user.id())
            .filter(token -> token.createdAt().plus(RESEND_EVERY).isAfter(clock.instant()))
            .isPresent();
    if (sentRecently) {
      throw new DomainException("RESEND_TOO_SOON", "wait a few minutes before asking again");
    }

    mail.sendVerification(user);
  }

  @GetMapping("/sessions")
  public List<SessionSummaryResponse> sessions() {
    Actor.User caller = caller();

    return sessions.listLive(caller.userId()).stream()
        .map(session -> SessionSummaryResponse.of(session, caller.sessionId()))
        .toList();
  }

  @DeleteMapping("/sessions/others")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeOtherSessions() {
    Actor.User caller = caller();

    sessions.revokeOthers(caller.userId(), caller.sessionId());
    AuthEvents.sessionsRevoked(caller.userId(), "others");
  }

  private MeResponse describe(User user) {
    return MeResponse.of(user, merchants.get(user.merchantId()));
  }

  private static Actor.User caller() {
    if (MerchantContext.current().actor() instanceof Actor.User user) {
      return user;
    }

    throw new IllegalStateException("the session filter lets only users reach /v1/me");
  }
}
