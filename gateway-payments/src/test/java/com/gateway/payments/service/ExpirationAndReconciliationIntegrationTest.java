package com.gateway.payments.service;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ChargeStatus;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.domain.EventSource;
import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.JobType;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentEvent;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.repository.JobRepository;
import com.gateway.payments.repository.PaymentRepository;
import com.gateway.payments.repository.ReconciliationDivergenceRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ExpirationAndReconciliationIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired ExpirationService expiration;
  @Autowired ReconciliationService reconciliation;
  @Autowired JobRunner runner;
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired ReconciliationDivergenceRepository divergences;
  @Autowired PlatformTransactionManager txManager;

  Payment reload(Payment p) {
    return payments.findById(p.id()).orElseThrow();
  }

  @Test
  void expirationAsksTheBankFirst() {
    Payment p = newCharge(1000);
    bank.markPaid(p.id(), "E2E" + p.id(), Money.brl(1000));
    clock.advance(Duration.ofHours(2));

    expiration.expireDue(clock.instant());

    Payment after = reload(p);
    assertThat(after.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(after.pix().endToEndId()).isEqualTo("E2E" + p.id());
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(EventSource.RECONCILIATION);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void expirationMarksExpiredWhenBankSaysActive() {
    Payment p = newCharge(1000);
    clock.advance(Duration.ofHours(2));

    expiration.expireDue(clock.instant());

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.EXPIRED);
    assertThat(bank.callsFor(p.id())).contains("findCharge:" + p.id(), "cancelCharge:" + p.id());
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.expired");
  }

  @Test
  void expirationBeforeTheGraceIsANoOp() {
    Payment p = newCharge(1000);
    clock.advance(Duration.ofSeconds(3600 + 60)); // expired, but inside the 5 min grace

    expiration.expireDue(clock.instant());

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void reconciliationCompletesWhatTheWebhookMissed() {
    Payment p = newCharge(1000);
    clock.advance(Duration.ofHours(2));
    expiration.expireDue(clock.instant());
    assertThat(reload(p).status()).isEqualTo(PaymentStatus.EXPIRED);
    bank.markPaid(p.id(), "E2E" + p.id(), Money.brl(1000));

    reconciliation.reconcile(merchant, ProviderEnvironment.TEST, clock.instant().minus(Duration.ofDays(1)), clock.instant());

    Payment after = reload(p);
    assertThat(after.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(EventSource.RECONCILIATION);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.expired", "payment.completed");
  }

  @Test
  void reconciliationOpensDivergenceForTheRest() {
    Payment p = newCharge(1000);
    new TransactionTemplate(txManager).executeWithoutResult(s -> {
      Payment loaded = reload(p);
      payments.save(loaded, List.of(loaded.markCompleted("E2E" + p.id(), Money.brl(1000), clock.instant(), EventSource.PROVIDER_WEBHOOK)));
    });
    bank.setStatus(p.id(), ChargeStatus.REMOVED_BY_PSP);

    reconciliation.reconcileAll(clock.instant().plus(Duration.ofMinutes(30)));
    reconciliation.reconcileAll(clock.instant().plus(Duration.ofMinutes(45))); // a second run does not duplicate it

    assertThat(divergences.open())
        .filteredOn(d -> d.paymentId().equals(p.id()))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.gatewayStatus()).isEqualTo("COMPLETED");
          assertThat(d.providerStatus()).isEqualTo("REMOVED_BY_PSP");
        });
    List<PaymentEvent> events = payments.events(p.id());
    assertThat(events.getLast().type()).isEqualTo("completed");
    assertThat(reload(p).status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void theReconcileJobIsRescheduledNeverDone() {
    // The singleton may already exist, rescheduled by another test's runner pass.
    jdbc.update("DELETE FROM payments.jobs WHERE type = 'RECONCILE'");
    new TransactionTemplate(txManager).executeWithoutResult(s -> jobs.enqueue(Job.reconcile(clock)));
    clock.advance(Duration.ofMinutes(1));

    while (runner.runDue(clock.instant()) > 0) {}

    Job job = jobs.findByTypeAndRef(JobType.RECONCILE, "all").orElseThrow();
    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.attempts()).isZero();
    assertThat(job.nextRunAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
  }

  @Test
  void theExpireJobRunsThroughTheRunner() {
    Payment p = newCharge(1000);
    clock.advance(Duration.ofHours(2));

    // The context is shared: other tests' jobs are due too, so drain until nothing is left to claim.
    while (runner.runDue(clock.instant()) > 0) {}

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.EXPIRED);
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id()).orElseThrow().status()).isEqualTo("DONE");
  }

  @Test
  void bankCompletedWithoutPixLeavesThePaymentAlone() {
    Payment p = newCharge(1000);
    bank.setStatus(p.id(), ChargeStatus.COMPLETED); // no pix[]
    clock.advance(Duration.ofHours(2));

    expiration.expireDue(clock.instant());

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.PENDING);
  }

  /** A payment left CREATED, as if the process died between the insert and the bank's answer. */
  Payment stuckCreated() {
    Payment p = Payment.create(merchant, ProviderEnvironment.TEST, "ITAU", Money.brl(1000), null, null, null, 3600, clock);
    return new TransactionTemplate(txManager).execute(s -> payments.save(p, List.of(p.createdEvent())));
  }

  @Test
  void stuckCreatedWithAnActiveChargeIsAdopted() {
    Payment p = stuckCreated();
    bank.createCharge(null, p.id(), Money.brl(1000), 3600, null, null, null);
    clock.advance(Duration.ofMinutes(11));

    expiration.sweepStuckCreated(clock.instant());

    Payment after = reload(p);
    assertThat(after.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(after.pix().pixCopiaECola()).isEqualTo("00020101021226" + p.id());
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(EventSource.SYSTEM);
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id())).isPresent();
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
  }

  @Test
  void stuckCreatedAlreadyPaidIsAdoptedThenCompleted() {
    Payment p = stuckCreated();
    bank.createCharge(null, p.id(), Money.brl(1000), 3600, null, null, null);
    bank.markPaid(p.id(), "E2E" + p.id(), Money.brl(1000));
    clock.advance(Duration.ofMinutes(11));

    expiration.sweepStuckCreated(clock.instant());

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void stuckCreatedUnknownToTheBankFails() {
    Payment p = stuckCreated();
    clock.advance(Duration.ofMinutes(11));

    expiration.sweepStuckCreated(clock.instant());

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(EventSource.SYSTEM);
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  @Test
  void youngCreatedIsLeftToItsInFlightCall() {
    Payment p = stuckCreated();
    clock.advance(Duration.ofMinutes(2));

    expiration.sweepStuckCreated(clock.instant());

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.CREATED);
  }

  @Test
  void reconciliationOpensADivergenceForAFailedPaymentPaidAtTheBank() {
    bank.landNextCreateThenFailWith(new com.gateway.kernel.provider.ProviderException(
        com.gateway.kernel.provider.ProviderException.Code.DECLINED, 422, null, "declined but created"));
    assertThatThrownBy(() -> newCharge(1000)).isInstanceOf(com.gateway.kernel.errors.DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    bank.markPaid(p.id(), "E2E" + p.id(), Money.brl(1000));

    reconciliation.reconcileAll(clock.instant().plus(Duration.ofMinutes(30)));
    reconciliation.reconcileAll(clock.instant().plus(Duration.ofMinutes(45)));

    assertThat(reload(p).status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id())).singleElement()
        .satisfies(d -> assertThat(d.detail()).startsWith("paid at bank while FAILED"));
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  @Test
  void backoffDoublesAndCapsAtOneDay() {
    assertThat(JobRunner.backoff(0)).isEqualTo(Duration.ofMinutes(1));
    assertThat(JobRunner.backoff(3)).isEqualTo(Duration.ofMinutes(8));
    assertThat(JobRunner.backoff(30)).isEqualTo(Duration.ofHours(24));
  }
}
