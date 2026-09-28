package com.gateway.payments.jobs;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.boleto.BoletoPollingService;
import java.time.Duration;
import java.time.Instant;

/** Asks the bank whether a Bolecode's barcode was paid, every 6 h until the limit date. */
public class PollBoletoJob implements JobHandler {
  private final BoletoPollingService boletoPolling;
  private final PaymentsProperties properties;

  public PollBoletoJob(BoletoPollingService boletoPolling, PaymentsProperties properties) {
    this.boletoPolling = boletoPolling;
    this.properties = properties;
  }

  @Override
  public JobType type() {
    return JobType.POLL_BOLETO;
  }

  @Override
  public boolean run(String refId, Instant now) {
    return boletoPolling.check(refId, EventSource.PROVIDER_POLL);
  }

  /**
   * POLL_BOLETO looks again every {@code boletoPollEvery} (6 h) while the bank says open; a failure
   * (bank unreachable) backs off like any job but never waits longer than the poll period itself.
   */
  @Override
  public Job reschedule(Job job, Instant now, String error, boolean failed) {
    Duration wait =
        failed && JobBackoff.backoff(job.attempts()).compareTo(properties.boletoPollEvery()) < 0
            ? JobBackoff.backoff(job.attempts())
            : properties.boletoPollEvery();
    return job.reschedule(now.plus(wait), error, properties.boletoPollMaxAttempts());
  }
}
