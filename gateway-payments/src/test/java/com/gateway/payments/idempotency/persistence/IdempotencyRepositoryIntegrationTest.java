package com.gateway.payments.idempotency.persistence;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.TestApp;
import com.gateway.payments.idempotency.IdempotencyKey;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class IdempotencyRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired IdempotencyRepository repository;

  final Clock clock = Clock.systemUTC();

  @Test
  void firstInsertWinsSecondWithSameKeyDoesNot() {
    MerchantId merchant = MerchantId.next();
    IdempotencyKey k = IdempotencyKey.begin(merchant, "req-1", "hash-1", clock);

    assertThat(repository.insertIfAbsent(k)).isTrue();
    assertThat(repository.insertIfAbsent(k)).isFalse();
  }

  @Test
  void sameKeyForADifferentMerchantIsIndependent() {
    IdempotencyKey k1 = IdempotencyKey.begin(MerchantId.next(), "same-key", "hash-1", clock);
    IdempotencyKey k2 = IdempotencyKey.begin(MerchantId.next(), "same-key", "hash-1", clock);

    assertThat(repository.insertIfAbsent(k1)).isTrue();
    assertThat(repository.insertIfAbsent(k2)).isTrue();
  }

  @Test
  void finishRecordsTheResponse() {
    MerchantId merchant = MerchantId.next();
    IdempotencyKey k = IdempotencyKey.begin(merchant, "req-2", "hash-2", clock);
    repository.insertIfAbsent(k);

    // resourceId is a CHAR(26) column meant for a payment ULID; a short value would come back
    // space-padded by Postgres' bpchar, so use a real ULID here rather than asserting around that.
    String resourceId = Ulid.next();
    repository.finish(k.finish(201, "{\"id\":\"abc\"}", resourceId));

    IdempotencyKey found = repository.find(merchant, "req-2").orElseThrow();
    assertThat(found.responseCode()).isEqualTo(201);
    assertThat(found.responseBody()).isEqualTo("{\"id\":\"abc\"}");
    assertThat(found.resourceId()).isEqualTo(resourceId);
  }

  @Test
  void tenConcurrentInsertsOfTheSameKeyGiveExactlyOneWinner() throws InterruptedException {
    MerchantId merchant = MerchantId.next();
    String key = "concurrent-key";
    int threads = 10;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();

    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              IdempotencyKey k = IdempotencyKey.begin(merchant, key, "hash-x", clock);
              ready.countDown();
              try {
                go.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              if (repository.insertIfAbsent(k)) {
                winners.incrementAndGet();
              }
            });
      }
      ready.await();
      go.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(winners.get()).isEqualTo(1);
    assertThat(repository.find(merchant, key)).isPresent();
  }

  @Test
  void deleteOlderThanRemovesOldRows() {
    MerchantId merchant = MerchantId.next();
    IdempotencyKey k =
        IdempotencyKey.begin(
            merchant,
            "old-key",
            "hash",
            Clock.fixed(java.time.Instant.parse("2020-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));
    repository.insertIfAbsent(k);

    int deleted = repository.deleteOlderThan(java.time.Instant.parse("2025-01-01T00:00:00Z"));
    assertThat(deleted).isEqualTo(1);
    assertThat(repository.find(merchant, "old-key")).isEmpty();
  }
}
