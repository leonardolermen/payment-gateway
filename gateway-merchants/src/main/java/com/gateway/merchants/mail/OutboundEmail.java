package com.gateway.merchants.mail;

import com.gateway.kernel.ids.Ulid;
import java.time.Instant;

/** An e-mail waiting for the SEND_EMAIL job: written with the change that caused it, sent after. */
public record OutboundEmail(
    String id,
    String recipient,
    String subject,
    String textBody,
    String htmlBody,
    Instant createdAt) {

  public static OutboundEmail queue(Email email, Instant now) {
    return new OutboundEmail(
        Ulid.next(), email.to(), email.subject(), email.text(), email.html(), now);
  }

  public Email asEmail() {
    return new Email(recipient, subject, textBody, htmlBody);
  }
}
