package com.gateway.payments.repository;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.TestApp;
import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.JobType;
import com.gateway.payments.domain.OutboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class JobsAndOutboxClaimIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired JobRepository jobs;
  @Autowired OutboxRepository outbox;
  @Autowired PlatformTransactionManager txManager;

  final Clock clock = Clock.systemUTC();
  TransactionTemplate tx;

  TransactionTemplate tx() {
    if (tx == null) {
      tx = new TransactionTemplate(txManager);
    }
    return tx;
  }

  // --- jobs ---

  /**
   * {@code @Repository} translates data-access exceptions, so the raw {@code IllegalStateException}
   * from {@code JobRepositoryImpl.claimDue} arrives wrapped as {@code InvalidDataAccessApiUsageException}
   * with the original as its root cause — same shape as {@code DeliveryRepositoryImpl.claimDue}'s
   * own test in webhook-delivery.
   */
  @Test
  void claimDueOutsideATransactionThrows() {
    assertThatThrownBy(() -> jobs.claimDue(Instant.now(), 10, Duration.ofMinutes(1)))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void enqueueReturnsWhetherItInsertedTheRow() {
    Job reconcile = Job.reconcile(clock);
    Boolean first = tx().execute(status -> jobs.enqueue(reconcile));
    Boolean second = tx().execute(status -> jobs.enqueue(Job.reconcile(clock))); // same (type, ref_id="all"): conflicts

    assertThat(first).isTrue();
    assertThat(second).isFalse();
  }

  @Test
  void twoConcurrentClaimsGetDisjointSets() throws InterruptedException {
    for (int i = 0; i < 20; i++) {
      Job j = Job.processWebhook(Ulid.next(), clock);
      tx().executeWithoutResult(status -> jobs.enqueue(j));
    }

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    try {
      var f1 =
          pool.submit(
              () -> {
                ready.countDown();
                awaitQuietly(go);
                return tx().execute(status -> jobs.claimDue(Instant.now(), 10, Duration.ofMinutes(5)));
              });
      var f2 =
          pool.submit(
              () -> {
                ready.countDown();
                awaitQuietly(go);
                return tx().execute(status -> jobs.claimDue(Instant.now(), 10, Duration.ofMinutes(5)));
              });
      ready.await();
      go.countDown();
      List<Job> batch1 = f1.get(30, TimeUnit.SECONDS);
      List<Job> batch2 = f2.get(30, TimeUnit.SECONDS);

      Set<String> ids1 = batch1.stream().map(Job::id).collect(Collectors.toSet());
      Set<String> ids2 = batch2.stream().map(Job::id).collect(Collectors.toSet());
      assertThat(ids1).doesNotContainAnyElementsOf(ids2);
      assertThat(ids1.size() + ids2.size()).isGreaterThan(0);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anExpiredLeaseBecomesClaimableAgain() {
    Job j = Job.processWebhook(Ulid.next(), clock);
    tx().executeWithoutResult(status -> jobs.enqueue(j));

    // First claim, with a lease so short it is already expired by the time we look again.
    List<Job> firstClaim = tx().execute(status -> jobs.claimDue(Instant.now(), 10, Duration.ofMillis(1)));
    assertThat(firstClaim).extracting(Job::id).contains(j.id());

    sleep(20);

    List<Job> secondClaim = tx().execute(status -> jobs.claimDue(Instant.now(), 10, Duration.ofMillis(1)));
    assertThat(secondClaim).extracting(Job::id).contains(j.id());
  }

  // --- outbox ---

  @Test
  void claimPendingOutsideATransactionThrows() {
    assertThatThrownBy(() -> outbox.claimPending(10, Duration.ofMinutes(1)))
        .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void claimPendingReturnsPendingMessagesAndMarkSentTakesThemOut() {
    OutboxMessage m = new OutboxMessage(Ulid.next(), MerchantId.next(), Ulid.next(), null, "payment.created", "{}", "PENDING", null, Instant.now());
    tx().executeWithoutResult(status -> outbox.append(m));

    List<OutboxMessage> claimed = tx().execute(status -> outbox.claimPending(10, Duration.ofMinutes(5)));
    assertThat(claimed).extracting(OutboxMessage::id).contains(m.id());

    outbox.markSent(m.id());

    List<OutboxMessage> claimedAgain = tx().execute(status -> outbox.claimPending(10, Duration.ofMinutes(5)));
    assertThat(claimedAgain).extracting(OutboxMessage::id).doesNotContain(m.id());
  }

  @Test
  void releaseMakesAClaimedMessageClaimableAgainImmediately() {
    OutboxMessage m = new OutboxMessage(Ulid.next(), MerchantId.next(), Ulid.next(), null, "payment.created", "{}", "PENDING", null, Instant.now());
    tx().executeWithoutResult(status -> outbox.append(m));

    tx().executeWithoutResult(status -> outbox.claimPending(10, Duration.ofMinutes(5)));
    outbox.release(m.id());

    List<OutboxMessage> claimedAgain = tx().execute(status -> outbox.claimPending(10, Duration.ofMinutes(5)));
    assertThat(claimedAgain).extracting(OutboxMessage::id).contains(m.id());
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
