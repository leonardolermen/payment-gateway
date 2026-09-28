package com.gateway.payments.jobs;

import com.gateway.payments.payment.PaymentExpiration;
import java.time.Instant;

/** Expires one PENDING payment on its limit date, asking the bank first. */
public class ExpirePaymentJob implements JobHandler {
  private final PaymentExpiration expiration;
  private final JobBackoff backoff;

  public ExpirePaymentJob(PaymentExpiration expiration, JobBackoff backoff) {
    this.expiration = expiration;
    this.backoff = backoff;
  }

  @Override
  public JobType type() {
    return JobType.EXPIRE_PAYMENT;
  }

  @Override
  public boolean run(String refId, Instant now) {
    expiration.expireOne(refId, now);
    return true;
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return backoff.retry(job, now, error);
  }
}
