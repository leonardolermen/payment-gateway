package com.gateway.app.mail;

import com.gateway.merchants.mail.MailGateway;
import com.gateway.merchants.mail.OutboundEmail;
import com.gateway.merchants.mail.OutboundEmailService;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;
import java.util.Optional;

public class SendEmailJob implements JobHandler {
  private final OutboundEmailService outbound;
  private final MailGateway mail;
  private final JobBackoff backoff;

  public SendEmailJob(OutboundEmailService outbound, MailGateway mail, JobBackoff backoff) {
    this.outbound = outbound;
    this.mail = mail;
    this.backoff = backoff;
  }

  @Override
  public JobType type() {
    return JobType.SEND_EMAIL;
  }

  /** A row that is gone was sent by an earlier run whose ack was lost: done, not an error. */
  @Override
  public boolean run(String refId, Instant now) {
    Optional<OutboundEmail> email = outbound.find(refId);
    if (email.isEmpty()) {
      return true;
    }

    mail.send(email.get().asEmail());
    outbound.delete(refId);

    return true;
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return backoff.retry(job, now, error);
  }
}
