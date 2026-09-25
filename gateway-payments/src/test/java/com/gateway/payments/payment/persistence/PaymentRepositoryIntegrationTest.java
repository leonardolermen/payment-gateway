package com.gateway.payments.payment.persistence;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.TestApp;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class PaymentRepositoryIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired PaymentRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
  TransactionTemplate tx;

  TransactionTemplate tx() {
    if (tx == null) {
      tx = new TransactionTemplate(txManager);
    }
    return tx;
  }

  Payment fresh() {
    return Payment.create(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(15990), "order-8812", "Order 8812", null, 3600, clock);
  }

  /** Persists a freshly-created payment (its own "created" event) inside a short transaction. */
  void persistNew(Payment p) {
    tx().executeWithoutResult(status -> repository.save(p, List.of(p.createdEvent())));
  }

  @Test
  void savingANewPaymentWritesThePaymentsRowAndTheCreatedEvent() {
    Payment p = fresh();
    persistNew(p);

    Long paymentRows = jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE id = ?", Long.class, p.id());
    assertThat(paymentRows).isEqualTo(1);
    Long eventRows = jdbc.queryForObject("SELECT count(*) FROM payments.payment_events WHERE payment_id = ?", Long.class, p.id());
    assertThat(eventRows).isEqualTo(1);
  }

  @Test
  void findByIdRehydratesPixVersionAndStatus() {
    Payment p = fresh();
    PaymentEvent pendingEvent =
        p.markPending(new PixDetails(p.id(), "000201...copia-e-cola", "pix.example.com/loc", null), Instant.parse("2026-09-24T13:00:00Z"));
    tx().executeWithoutResult(status -> repository.save(p, List.of(p.createdEvent(), pendingEvent)));

    Payment loaded = repository.findById(p.id()).orElseThrow();
    assertThat(loaded.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(loaded.version()).isEqualTo(2);
    assertThat(loaded.pix().pixCopiaECola()).isEqualTo("000201...copia-e-cola");
    assertThat(loaded.pix().location()).isEqualTo("pix.example.com/loc");
    assertThat(loaded.amount()).isEqualTo(Money.brl(15990));
    assertThat(loaded.merchantId()).isEqualTo(p.merchantId());
  }

  @Test
  void findByMerchantAndIdOnlyMatchesTheOwningMerchant() {
    Payment p = fresh();
    persistNew(p);
    assertThat(repository.findByMerchantAndId(p.merchantId(), p.id())).isPresent();
    assertThat(repository.findByMerchantAndId(MerchantId.next(), p.id())).isEmpty();
  }

  @Test
  void aStaleSecondSaveThrowsOptimisticLockingFailure() {
    Payment p = fresh();
    persistNew(p);

    // Two independent copies of the same persisted payment, as two callers loading concurrently would.
    Payment copy1 = repository.findById(p.id()).orElseThrow();
    Payment copy2 = repository.findById(p.id()).orElseThrow();

    PaymentEvent e1 = copy1.markPending(new PixDetails(p.id(), "a", "b", null), Instant.now());
    PaymentEvent e2 = copy2.markPending(new PixDetails(p.id(), "c", "d", null), Instant.now());

    tx().executeWithoutResult(status -> repository.save(copy1, List.of(e1)));

    assertThatThrownBy(() -> tx().executeWithoutResult(status -> repository.save(copy2, List.of(e2))))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  /**
   * Two saves of the SAME payment inside one transaction (two webhook events landing back to back,
   * say) must not trip a false optimistic-lock failure. Before {@code clearAutomatically = true} on
   * {@code updateIfVersionMatches}, the bulk JPQL update bypassed the persistence context, so the
   * second {@code findById} in this transaction would return the stale cached entity from before
   * the first save — with the pre-update version — and the second save would then compute a stale
   * {@code expectedVersion} and fail even though nothing else touched the row.
   */
  @Test
  void twoSavesOfTheSamePaymentInOneTransactionDoNotFalselyConflict() {
    Payment p = fresh();
    persistNew(p);

    tx().executeWithoutResult(
        status -> {
          Payment loaded1 = repository.findById(p.id()).orElseThrow();
          PaymentEvent pending = loaded1.markPending(new PixDetails(p.id(), "a", "b", null), Instant.now());
          repository.save(loaded1, List.of(pending));

          Payment loaded2 = repository.findById(p.id()).orElseThrow();
          PaymentEvent completed = loaded2.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
          repository.save(loaded2, List.of(completed));
        });

    Payment reloaded = repository.findById(p.id()).orElseThrow();
    assertThat(reloaded.version()).isEqualTo(3);
    assertThat(reloaded.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(repository.events(p.id())).hasSize(3);
  }

  /** Persists a payment that has already transitioned once, past its own creation: both events go in the same insert. */
  void persistWithOneTransition(Payment p, PaymentEvent transitionEvent) {
    tx().executeWithoutResult(status -> repository.save(p, List.of(p.createdEvent(), transitionEvent)));
  }

  @Test
  void findPendingOlderThanReturnsOnlyPendingBeforeTheCutoff() {
    Payment expiredPending = fresh();
    PaymentEvent e1 = expiredPending.markPending(new PixDetails(expiredPending.id(), "a", "b", null), Instant.parse("2026-09-24T12:30:00Z"));
    persistWithOneTransition(expiredPending, e1);

    Payment stillFreshPending = fresh();
    PaymentEvent e2 = stillFreshPending.markPending(new PixDetails(stillFreshPending.id(), "a", "b", null), Instant.parse("2026-09-25T12:30:00Z"));
    persistWithOneTransition(stillFreshPending, e2);

    Payment created = fresh(); // status CREATED, not PENDING — must not match
    persistNew(created);

    // Other tests in this class share the same Testcontainers database (no per-test rollback), so
    // assert on membership rather than an exact list: only that this test's own PENDING-and-overdue
    // payment is included and its own not-yet-due / not-PENDING siblings are excluded.
    List<Payment> due = repository.findPendingOlderThan(Instant.parse("2026-09-24T18:00:00Z"), 100);
    assertThat(due).extracting(Payment::id).contains(expiredPending.id()).doesNotContain(stillFreshPending.id(), created.id());
    assertThat(due).allSatisfy(p -> assertThat(p.status()).isEqualTo(PaymentStatus.PENDING));
  }

  @Test
  void findByStatusInFiltersByStatusAndCreationTime() {
    Payment p = fresh();
    persistNew(p);
    assertThat(repository.findByStatusIn(Set.of(PaymentStatus.CREATED), Instant.parse("2026-09-24T11:00:00Z"), 100))
        .extracting(Payment::id)
        .contains(p.id());
    assertThat(repository.findByStatusIn(Set.of(PaymentStatus.COMPLETED), Instant.parse("2026-09-24T11:00:00Z"), 100)).isEmpty();
    assertThat(repository.findByStatusIn(Set.of(PaymentStatus.CREATED), Instant.parse("2026-09-24T13:00:00Z"), 100)).isEmpty();
  }

  @Test
  void findByStatusInRespectsTheLimit() {
    Payment a = fresh();
    persistNew(a);
    Payment b = fresh();
    persistNew(b);

    List<Payment> capped = repository.findByStatusIn(Set.of(PaymentStatus.CREATED), Instant.parse("2020-01-01T00:00:00Z"), 1);
    assertThat(capped).hasSize(1);
  }

  @Test
  void eventsComeBackInSequenceOrder() {
    Payment p = fresh();
    PaymentEvent pending = p.markPending(new PixDetails(p.id(), "a", "b", null), Instant.now());
    PaymentEvent completed = p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    tx().executeWithoutResult(status -> repository.save(p, List.of(p.createdEvent(), pending, completed)));

    List<PaymentEvent> events = repository.events(p.id());
    assertThat(events).extracting(PaymentEvent::sequence).containsExactly(1L, 2L, 3L);
    assertThat(events).extracting(PaymentEvent::type).containsExactly("created", "pending", "completed");
  }

  @Test
  void aBolecodeRoundTripsWithBothBlocksAndIsFoundByTxid() {
    BoletoDetails b = new BoletoDetails("00000007", null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    Payment p = Payment.createBolecode(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(500), "o-7", null, null, b, Instant.parse("2026-11-01T02:59:59Z"), clock);
    tx().executeWithoutResult(s -> repository.save(p, List.of(p.createdEvent())));
    tx().executeWithoutResult(s -> {
      Payment loaded = repository.findById(p.id()).orElseThrow();
      PaymentEvent ev = loaded.markPendingBolecode(new PixDetails("BL15000005206109000000000000007", "emv", null, null),
          loaded.boleto().withIssued("uuid-7", "7".repeat(47), "7".repeat(44), null), Instant.parse("2026-11-01T02:59:59Z"), EventSource.API);
      repository.save(loaded, List.of(ev));
    });

    Payment back = repository.findById(p.id()).orElseThrow();
    assertThat(back.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(back.boleto().nossoNumero()).isEqualTo("00000007");
    assertThat(back.boleto().linhaDigitavel()).isEqualTo("7".repeat(47));
    assertThat(back.boleto().paymentLimitDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(back.pix().txid()).isEqualTo("BL15000005206109000000000000007");
    assertThat(jdbc.queryForObject("SELECT method FROM payments.payments WHERE id = ?", String.class, p.id())).isEqualTo("BOLECODE");
    assertThat(repository.findByMerchantAndTxid(p.merchantId(), "ITAU", "BL15000005206109000000000000007")).isPresent();
    assertThat(repository.findByMerchantAndTxid(MerchantId.next(), "ITAU", "BL15000005206109000000000000007")).isEmpty();
  }

  @Test
  void aPixPaymentStillReadsBackWithANullBoletoAndItsNestedTxid() {
    Payment p = fresh();
    tx().executeWithoutResult(s -> repository.save(p, List.of(p.createdEvent())));
    Payment back = repository.findById(p.id()).orElseThrow();
    assertThat(back.method()).isEqualTo(PaymentMethod.PIX);
    assertThat(back.boleto()).isNull();
    assertThat(back.pix().txid()).isEqualTo(p.id());
    assertThat(jdbc.queryForObject("SELECT details->'pix'->>'txid' FROM payments.payments WHERE id = ?", String.class, p.id())).isEqualTo(p.id());
    assertThat(repository.findByMerchantAndTxid(p.merchantId(), "ITAU", p.id())).isPresent();
  }
}
