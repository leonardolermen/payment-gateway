package com.gateway.app.api.auth;

import com.gateway.merchants.MailProperties;
import com.gateway.merchants.mail.Email;
import com.gateway.merchants.mail.MailTemplates;
import com.gateway.merchants.mail.OutboundEmailService;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.usertoken.UserToken;
import com.gateway.merchants.usertoken.UserTokenService;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The panel's link e-mails. Token, outbound row and SEND_EMAIL job commit together: a token without
 * its e-mail is a link nobody received, an e-mail without its job is one nobody sends.
 */
@Component
public class AuthMailService {
  private static final Duration VERIFY_TTL = Duration.ofHours(24);
  private static final Duration RESET_TTL = Duration.ofHours(1);
  private static final Duration INVITE_TTL = Duration.ofDays(7);

  private final UserTokenService tokens;
  private final OutboundEmailService outbound;
  private final JobRepository jobs;
  private final MailProperties properties;
  private final Clock clock;

  public AuthMailService(
      UserTokenService tokens,
      OutboundEmailService outbound,
      JobRepository jobs,
      MailProperties properties,
      Clock clock) {
    this.tokens = tokens;
    this.outbound = outbound;
    this.jobs = jobs;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public void sendVerification(User user) {
    tokens.invalidateOpen(UserToken.Kind.VERIFY_EMAIL, user.id());
    String plain = issue(UserToken.Kind.VERIFY_EMAIL, user, Map.of(), VERIFY_TTL);

    send(MailTemplates.verifyEmail(user.email().value(), user.name(), link("/verify/", plain)));
  }

  @Transactional
  public void sendPasswordReset(User user) {
    tokens.invalidateOpen(UserToken.Kind.RESET_PASSWORD, user.id());
    String plain = issue(UserToken.Kind.RESET_PASSWORD, user, Map.of(), RESET_TTL);

    send(MailTemplates.resetPassword(user.email().value(), user.name(), link("/reset/", plain)));
  }

  @Transactional
  public void sendInvite(Merchant merchant, EmailAddress email, Role role) {
    Map<String, String> payload = Map.of("email", email.value(), "role", role.name());
    String plain =
        tokens
            .issue(UserToken.Kind.INVITE, null, merchant.id(), payload, INVITE_TTL)
            .plain()
            .reveal();

    send(MailTemplates.invite(email.value(), merchant.name(), link("/invite/", plain)));
  }

  private String issue(UserToken.Kind kind, User user, Map<String, String> payload, Duration ttl) {
    return tokens.issue(kind, user.id(), user.merchantId(), payload, ttl).plain().reveal();
  }

  private String link(String path, String plain) {
    return properties.panelBaseUrl() + path + plain;
  }

  private void send(Email email) {
    String outboundId = outbound.enqueue(email);

    jobs.enqueue(Job.sendEmail(outboundId, clock));
  }
}
