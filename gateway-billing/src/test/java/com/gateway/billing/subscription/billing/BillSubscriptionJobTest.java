package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.BillingProperties;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BillSubscriptionJobTest {
  private static final Instant START = Instant.parse("2026-10-02T12:00:00Z");

  @Test
  void failuresPastTheRetryBudgetKeepTheJobPendingNeverDead() {
    PaymentsProperties payments = PaymentsProperties.defaults();
    BillSubscriptionJob handler =
        new BillSubscriptionJob(null, null, new JobBackoff(payments), BillingProperties.defaults());
    Job job = Job.billSubscription("sub_1", START, Clock.fixed(START, ZoneOffset.UTC));
    Instant now = START;

    for (int failure = 0; failure < payments.jobMaxAttempts() + 5; failure++) {
      now = now.plus(Duration.ofHours(1));
      job = handler.afterFailure(job, now, "boom");
    }

    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.attempts()).isEqualTo(payments.jobMaxAttempts() - 1);
    assertThat(job.lastError()).isEqualTo("boom");
    assertThat(job.nextRunAt()).isEqualTo(now.plus(Duration.ofHours(1)));
  }
}
