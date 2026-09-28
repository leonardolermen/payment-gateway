package com.gateway.payments.jobs;

import com.gateway.payments.inbox.WebhookInboxService;
import java.time.Instant;

/** Confirms and applies one stored webhook body. */
public class ProcessWebhookJob implements JobHandler {
  private final WebhookInboxService inbox;
  private final JobBackoff backoff;

  public ProcessWebhookJob(WebhookInboxService inbox, JobBackoff backoff) {
    this.inbox = inbox;
    this.backoff = backoff;
  }

  @Override
  public JobType type() {
    return JobType.PROCESS_WEBHOOK;
  }

  @Override
  public boolean run(String refId, Instant now) {
    inbox.process(refId);
    return true;
  }

  @Override
  public Job reschedule(Job job, Instant now, String error, boolean failed) {
    return backoff.retry(job, now, error);
  }
}
