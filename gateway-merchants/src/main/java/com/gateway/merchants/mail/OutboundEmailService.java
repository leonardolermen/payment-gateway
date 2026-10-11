package com.gateway.merchants.mail;

import com.gateway.merchants.mail.persistence.OutboundEmailRepository;
import java.time.Clock;
import java.util.Optional;

public class OutboundEmailService {
  private final OutboundEmailRepository emails;
  private final Clock clock;

  public OutboundEmailService(OutboundEmailRepository emails, Clock clock) {
    this.emails = emails;
    this.clock = clock;
  }

  /** Returns the id the SEND_EMAIL job carries. */
  public String enqueue(Email email) {
    OutboundEmail outbound = OutboundEmail.queue(email, clock.instant());

    emails.insert(outbound);

    return outbound.id();
  }

  public Optional<OutboundEmail> find(String id) {
    return emails.findById(id);
  }

  public void delete(String id) {
    emails.deleteById(id);
  }
}
