package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.BillingProperties;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DunningRetryJobTest {
  private static final Instant START = Instant.parse("2026-10-02T12:00:00Z");

  @Test
  void aLiveAttemptNeverSpendsRetriesNorKillsTheJob() {
    DunningRetryJob handler = new DunningRetryJob(null, null, BillingProperties.defaults());
    Job job = Job.dunningRetry("att_1", START, Clock.fixed(START, ZoneOffset.UTC));
    Instant now = START;

    for (int call = 0; call < 500; call++) {
      now = now.plus(Duration.ofHours(1));
      job = handler.notYet(job, now);
    }

    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.attempts()).isZero();
    assertThat(job.lastError()).isEqualTo(JobHandler.NOT_YET);
    assertThat(job.nextRunAt()).isEqualTo(now.plus(Duration.ofHours(1)));
  }

  @Test
  void failuresPastTheRetryBudgetKeepTheJobPendingNeverDead() {
    PaymentsProperties payments = PaymentsProperties.defaults();
    DunningRetryJob handler =
        new DunningRetryJob(null, new JobBackoff(payments), BillingProperties.defaults());
    Job job = Job.dunningRetry("att_1", START, Clock.fixed(START, ZoneOffset.UTC));
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
