package com.gateway.payments.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JobTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void expireAtBuildsAPendingJobForThePayment() {
    Instant when = Instant.parse("2026-09-24T13:00:00Z");
    Job j = Job.expireAt("payment-1", when, clock);
    assertThat(j.id()).matches("[a-zA-Z0-9]{26,35}");
    assertThat(j.type()).isEqualTo(JobType.EXPIRE_PAYMENT);
    assertThat(j.refId()).isEqualTo("payment-1");
    assertThat(j.nextRunAt()).isEqualTo(when);
    assertThat(j.attempts()).isZero();
    assertThat(j.status()).isEqualTo("PENDING");
  }

  @Test
  void processWebhookRunsNow() {
    Job j = Job.processWebhook("inbox-1", clock);
    assertThat(j.type()).isEqualTo(JobType.PROCESS_WEBHOOK);
    assertThat(j.refId()).isEqualTo("inbox-1");
    assertThat(j.nextRunAt()).isEqualTo(clock.instant());
  }

  @Test
  void pollRefundRunsAtTheGivenTime() {
    Instant firstAt = Instant.parse("2026-09-24T14:00:00Z");
    Job j = Job.pollRefund("refund-1", firstAt, clock);
    assertThat(j.type()).isEqualTo(JobType.POLL_REFUND);
    assertThat(j.refId()).isEqualTo("refund-1");
    assertThat(j.nextRunAt()).isEqualTo(firstAt);
  }

  @Test
  void reconcileTargetsAllAndRunsNow() {
    Job j = Job.reconcile(clock);
    assertThat(j.type()).isEqualTo(JobType.RECONCILE);
    assertThat(j.refId()).isEqualTo("all");
    assertThat(j.nextRunAt()).isEqualTo(clock.instant());
  }

  @Test
  void rescheduleGoesDeadAtMaxAttempts() {
    Job j = Job.processWebhook("inbox-1", clock);
    Instant next = Instant.parse("2026-09-24T13:00:00Z");
    Job r1 = j.reschedule(next, "boom", 3);
    assertThat(r1.attempts()).isEqualTo(1);
    assertThat(r1.status()).isEqualTo("PENDING");
    assertThat(r1.lastError()).isEqualTo("boom");
    assertThat(r1.claimedAt()).isNull();

    Job r2 = r1.reschedule(next, "boom again", 3);
    assertThat(r2.attempts()).isEqualTo(2);
    assertThat(r2.status()).isEqualTo("PENDING");

    Job r3 = r2.reschedule(next, "boom once more", 3);
    assertThat(r3.attempts()).isEqualTo(3);
    assertThat(r3.status()).isEqualTo("DEAD");
  }

  @Test
  void doneMarksTheJobDone() {
    Job j = Job.processWebhook("inbox-1", clock);
    Job d = j.done();
    assertThat(d.status()).isEqualTo("DONE");
  }
}
