package com.gateway.payments.jobs.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobQuery;
import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The operator's writes on the jobs table: both are conditional UPDATEs, and the lease guard is
 * what keeps them off a job a worker is running right now.
 */
class JobRepositoryAdminIntegrationTest extends ServiceIntegrationTestBase {
  private static final Duration LEASE = Duration.ofMinutes(2);

  @Autowired JobRepository jobs;
  @Autowired JobRunner runner;

  @Test
  void theOperatorSeesDeadJobsFirstAndRerunsOrGivesUp() {
    Instant now = clock.instant();

    Job dead = deadExpiration(now);
    Job due = pending(now.minusSeconds(60), null);
    Job claimed = pending(now.minusSeconds(60), now);
    List<String> mine = List.of(dead.id(), due.id(), claimed.id());

    List<String> listed =
        jobs.find(new JobQuery(null, null, JobQuery.MAX_LIMIT)).stream()
            .map(Job::id)
            .filter(mine::contains)
            .toList();
    assertThat(listed).first().isEqualTo(dead.id());
    assertThat(jobs.find(new JobQuery("DEAD", JobType.EXPIRE_PAYMENT, JobQuery.MAX_LIMIT)))
        .extracting(Job::status)
        .containsOnly("DEAD");

    assertThat(jobs.forceDue(claimed.id(), now, LEASE)).isFalse();
    assertThat(jobs.findById(claimed.id())).contains(claimed);

    assertThat(jobs.forceDue(dead.id(), now, LEASE)).isTrue();
    Job revived = jobs.findById(dead.id()).orElseThrow();
    assertThat(revived.status()).isEqualTo("PENDING");
    assertThat(revived.nextRunAt()).isEqualTo(now);
    assertThat(revived.attempts()).isEqualTo(dead.attempts());
    assertThat(revived.claimedAt()).isNull();
    assertThat(revived.lastError()).isEqualTo(dead.lastError());

    assertThat(jobs.giveUp(due.id(), "bank says it never existed", now, LEASE)).isTrue();
    Job givenUp = jobs.findById(due.id()).orElseThrow();
    assertThat(givenUp.status()).isEqualTo("DEAD");
    assertThat(givenUp.lastError()).isEqualTo("bank says it never existed");
    assertThat(givenUp.attempts()).isEqualTo(due.attempts());

    assertThat(jobs.giveUp(due.id(), "again", now, LEASE)).isFalse();
    assertThat(jobs.giveUp(claimed.id(), "while it runs", now, LEASE)).isFalse();
    assertThat(jobs.findById(claimed.id())).contains(claimed);

    // Given up before the run below, which would otherwise run the due job to DONE. The payment
    // does not exist: PaymentExpiration.expireOne answers "nothing to do", and the handler counts
    // that as done.
    runUntilIdle(now);
    assertThat(jobs.findById(dead.id()).orElseThrow().status()).isEqualTo("DONE");
  }

  @Test
  void aLeaseThatExpiredNoLongerProtectsTheJob() {
    Instant now = clock.instant();
    Job abandoned = pending(now.minusSeconds(600), now.minus(LEASE).minusSeconds(1));

    assertThat(jobs.forceDue(abandoned.id(), now, LEASE)).isTrue();
    assertThat(jobs.findById(abandoned.id()).orElseThrow().claimedAt()).isNull();
  }

  @Test
  void anUnknownIdUpdatesNothing() {
    Instant now = clock.instant();

    assertThat(jobs.forceDue("01UNKNOWN", now, LEASE)).isFalse();
    assertThat(jobs.giveUp("01UNKNOWN", "note", now, LEASE)).isFalse();
    assertThat(jobs.findById("01UNKNOWN")).isEmpty();
  }

  @Test
  void countsByStatusAndOverdue() {
    Instant now = clock.instant();
    long deadBefore = jobs.countByStatus("DEAD");
    long overdueBefore = jobs.countOverdue(now);

    deadExpiration(now);
    pending(now.minusSeconds(60), null);
    pending(now.plusSeconds(3600), null);

    assertThat(jobs.countByStatus("DEAD")).isEqualTo(deadBefore + 1);
    assertThat(jobs.countOverdue(now)).isEqualTo(overdueBefore + 1);
  }

  private Job deadExpiration(Instant now) {
    Job job = Job.expireAt(Ulid.next(), now.minusSeconds(3600), clock).reschedule(now, "boom", 1);
    jobs.enqueue(job);
    return job;
  }

  /** A billing type: BillingJobOwnersStub answers for it, so a stray run changes nothing. */
  private Job pending(Instant nextRunAt, Instant claimedAt) {
    Job draft = Job.expireOrder(Ulid.next(), nextRunAt, clock);
    Job job =
        new Job(
            draft.id(),
            draft.type(),
            draft.refId(),
            draft.nextRunAt(),
            draft.attempts(),
            draft.status(),
            claimedAt,
            draft.lastError(),
            draft.createdAt());
    jobs.enqueue(job);
    return job;
  }

  /** Other tests share the table; a batch of their due rows may come before ours. */
  private void runUntilIdle(Instant now) {
    for (int i = 0; i < 50 && runner.runDue(now) > 0; i++) {
      // keep claiming
    }
  }
}
