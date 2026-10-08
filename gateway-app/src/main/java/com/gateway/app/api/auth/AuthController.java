package com.gateway.app.api.auth;

import com.gateway.app.api.auth.dto.AcceptInviteRequest;
import com.gateway.app.api.auth.dto.ForgotPasswordRequest;
import com.gateway.app.api.auth.dto.LoginRequest;
import com.gateway.app.api.auth.dto.ResetPasswordRequest;
import com.gateway.app.api.auth.dto.SessionResponse;
import com.gateway.app.api.auth.dto.SignupRequest;
import com.gateway.app.api.auth.dto.VerifyEmailRequest;
import com.gateway.app.security.ClientIp;
import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.usertoken.UserToken;
import com.gateway.merchants.usertoken.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The panel's unauthenticated surface (spec 2026-10-08-usuarios-e-sessao, section 5). */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
  private static final Duration REFRESH_COOKIE_AGE = Duration.ofDays(30);

  private final SignupService signup;
  private final UserService users;
  private final SessionService sessions;
  private final UserTokenService tokens;
  private final AuthMailService mail;
  private final InviteAcceptService invites;

  public AuthController(
      SignupService signup,
      UserService users,
      SessionService sessions,
      UserTokenService tokens,
      AuthMailService mail,
      InviteAcceptService invites) {
    this.signup = signup;
    this.users = users;
    this.sessions = sessions;
    this.tokens = tokens;
    this.mail = mail;
    this.invites = invites;
  }

  @PostMapping("/signup")
  public ResponseEntity<SessionResponse> signup(
      @RequestBody SignupRequest request, HttpServletRequest http) {
    request.validate();

    EmailAddress email = new EmailAddress(request.email());
    User owner = signup.signup(request.storeName(), request.name(), email, request.password());

    return opened(open(owner, http), HttpStatus.CREATED);
  }

  @PostMapping("/login")
  public ResponseEntity<SessionResponse> login(
      @RequestBody LoginRequest request, HttpServletRequest http) {
    request.validate();

    User user =
        users
            .authenticate(new EmailAddress(request.email()), request.password())
            .orElseThrow(
                () -> new DomainException("INVALID_CREDENTIALS", "e-mail or password is wrong"));

    return opened(open(user, http), HttpStatus.OK);
  }

  @PostMapping("/refresh")
  public ResponseEntity<SessionResponse> refresh(HttpServletRequest http) {
    SessionService.Issued issued =
        SessionCookies.read(http).flatMap(sessions::refresh).orElseThrow(AuthController::expired);

    return opened(issued, HttpStatus.OK);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest http) {
    SessionCookies.read(http)
        .flatMap(sessions::findByRefresh)
        .ifPresent(session -> sessions.revoke(session.id()));

    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, SessionCookies.cleared().toString())
        .build();
  }

  @PostMapping("/password/forgot")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void forgot(@RequestBody ForgotPasswordRequest request) {
    request.validate();

    // Always 202: the answer must not say whether the e-mail exists.
    users.findActiveByEmail(new EmailAddress(request.email())).ifPresent(mail::sendPasswordReset);
  }

  @PostMapping("/password/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@RequestBody ResetPasswordRequest request) {
    request.validate();
    // Checked before consume: a rejected password must not spend the one-time link.
    users.requireStrongPassword(request.password());

    UserToken token = consume(UserToken.Kind.RESET_PASSWORD, request.token());

    users.resetPassword(token.userId(), request.password());
    sessions.revokeAll(token.userId());
  }

  @PostMapping("/email/verify")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(@RequestBody VerifyEmailRequest request) {
    request.validate();

    UserToken token = consume(UserToken.Kind.VERIFY_EMAIL, request.token());

    users.markEmailVerified(token.userId());
  }

  @PostMapping("/invite/accept")
  public ResponseEntity<SessionResponse> acceptInvite(
      @RequestBody AcceptInviteRequest request, HttpServletRequest http) {
    request.validate();

    User user = invites.accept(request.token(), request.name(), request.password());

    return opened(open(user, http), HttpStatus.CREATED);
  }

  private UserToken consume(UserToken.Kind kind, String plain) {
    return tokens.consume(kind, plain).orElseThrow(AuthController::linkGone);
  }

  private static DomainException linkGone() {
    return new DomainException("TOKEN_EXPIRED", "this link is no longer valid");
  }

  private SessionService.Issued open(User user, HttpServletRequest http) {
    return sessions.open(user.id(), ClientIp.of(http).value(), http.getHeader("User-Agent"));
  }

  private static DomainException expired() {
    return new DomainException("SESSION_EXPIRED", "sign in again");
  }

  private static ResponseEntity<SessionResponse> opened(
      SessionService.Issued issued, HttpStatus status) {
    String cookie = SessionCookies.refresh(issued.refreshToken(), REFRESH_COOKIE_AGE).toString();
    SessionResponse body =
        new SessionResponse(issued.accessToken().reveal(), issued.accessTtl().toSeconds());

    return ResponseEntity.status(status).header(HttpHeaders.SET_COOKIE, cookie).body(body);
  }
}
