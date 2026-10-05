package com.gateway.payments.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.payments.dispute.DisputeReason;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DivergencesIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired Divergences divergences;
  @Autowired ReconciliationDivergenceRepository repository;

  @Test
  void aSystemKindOpensOnceWhileOpenAndAnotherKindOpensBeside() {
    Payment payment = newCharge(1000);

    assertThat(divergences.open(payment, "PAID", "bank says paid")).isTrue();
    assertThat(divergences.open(payment, "PAID", "bank says paid again")).isFalse();
    assertThat(divergences.open(payment, "UNCONFIRMED_WEBHOOK", "no bank record")).isTrue();

    assertThat(repository.findByPayment(payment.id()))
        .hasSize(2)
        .extracting(ReconciliationDivergence::origin)
        .containsOnly(DivergenceOrigin.SYSTEM);
  }

  @Test
  void aSystemDivergenceUnderReviewStillBlocksAReDetectionOfTheSameKind() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");
    String id = repository.findByPayment(payment.id()).getFirst().id();

    divergences.review(id, "operator@gateway");

    assertThat(divergences.isOpen(payment, "PAID")).isTrue();
    assertThat(divergences.open(payment, "PAID", "re-detected by the next pass")).isFalse();
    assertThat(repository.findByPayment(payment.id())).hasSize(1);

    divergences.resolve(id, DivergenceResolution.CONFIRMED, "paid twice", "operator@gateway");

    assertThat(divergences.isOpen(payment, "PAID")).isFalse();
    assertThat(divergences.open(payment, "PAID", "a new occurrence after the decision")).isTrue();
  }

  @Test
  void oneOpenDisputePerPaymentAndItCoexistsWithASystemDivergence() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");

    ReconciliationDivergence dispute =
        divergences.openDispute(payment, DisputeReason.DUPLICATE, "charged twice");

    assertThat(dispute.origin()).isEqualTo(DivergenceOrigin.MERCHANT);
    assertThat(dispute.providerStatus()).isEqualTo("DISPUTE");
    assertThat(dispute.reason()).isEqualTo("DUPLICATE");
    assertThat(dispute.merchantNote()).isEqualTo("charged twice");
    assertThat(dispute.status()).isEqualTo(DivergenceStatus.OPEN);
    assertThat(repository.findOpenDispute(payment.id()))
        .get()
        .extracting(ReconciliationDivergence::id)
        .isEqualTo(dispute.id());

    assertThatThrownBy(() -> divergences.openDispute(payment, DisputeReason.OTHER, "again"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("DISPUTE_ALREADY_OPEN");
    assertThat(repository.findByPayment(payment.id())).hasSize(2);
  }

  @Test
  void aDisputeUnderReviewStillBlocksASecondOne() {
    Payment payment = newCharge(1000);
    ReconciliationDivergence dispute =
        divergences.openDispute(payment, DisputeReason.NOT_SETTLED, null);

    divergences.review(dispute.id(), "operator@gateway");

    assertThatThrownBy(() -> divergences.openDispute(payment, DisputeReason.OTHER, null))
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("DISPUTE_ALREADY_OPEN");
  }

  @Test
  void twoConcurrentDisputesOnOnePaymentOpenExactlyOne() throws Exception {
    Payment payment = newCharge(1000);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> attempt =
        () -> {
          start.await();
          try {
            divergences.openDispute(payment, DisputeReason.DUPLICATE, "race");
            return "OPENED";
          } catch (DomainException e) {
            return e.code();
          }
        };

    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<String> outcomes = new ArrayList<>();
    try {
      Future<String> first = executor.submit(attempt);
      Future<String> second = executor.submit(attempt);
      start.countDown();
      outcomes.add(first.get());
      outcomes.add(second.get());
    } finally {
      executor.shutdownNow();
    }

    assertThat(outcomes).containsExactlyInAnyOrder("OPENED", "DISPUTE_ALREADY_OPEN");
    assertThat(repository.findByPayment(payment.id())).hasSize(1);
  }

  @Test
  void reviewThenResolveRecordsWhoWhenAndWhyAndThenTheRowIsClosed() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");
    String id = repository.findByPayment(payment.id()).getFirst().id();

    ReconciliationDivergence reviewed = divergences.review(id, "operator@gateway");
    assertThat(reviewed.status()).isEqualTo(DivergenceStatus.UNDER_REVIEW);

    clock.advance(Duration.ofMinutes(5));
    Instant decidedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ReconciliationDivergence resolved =
        divergences.resolve(id, DivergenceResolution.CONFIRMED, "paid twice", "operator@gateway");

    assertThat(resolved.status()).isEqualTo(DivergenceStatus.RESOLVED);
    ReconciliationDivergence stored = divergences.get(id);
    assertThat(stored.status()).isEqualTo(DivergenceStatus.RESOLVED);
    assertThat(stored.resolution()).isEqualTo(DivergenceResolution.CONFIRMED);
    assertThat(stored.resolutionNote()).isEqualTo("paid twice");
    assertThat(stored.resolvedBy()).isEqualTo("operator@gateway");
    assertThat(stored.resolvedAt()).isEqualTo(decidedAt);
    assertThat(stored.updatedAt()).isEqualTo(decidedAt);

    assertThatThrownBy(
            () -> divergences.resolve(id, DivergenceResolution.FALSE_POSITIVE, null, "operator"))
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("DIVERGENCE_CLOSED");
  }

  @Test
  void aResolutionOfTheOtherOriginIsRefusedAndNothingChanges() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");
    String id = repository.findByPayment(payment.id()).getFirst().id();

    assertThatThrownBy(
            () -> divergences.resolve(id, DivergenceResolution.RESOLVED, null, "operator"))
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("RESOLUTION_NOT_ALLOWED");
    assertThat(divergences.get(id).status()).isEqualTo(DivergenceStatus.OPEN);
  }

  @Test
  void aStaleCopyLosesTheOptimisticUpdate() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");
    ReconciliationDivergence stale = repository.findByPayment(payment.id()).getFirst();

    clock.advance(Duration.ofSeconds(1));
    divergences.review(stale.id(), "first operator");
    stale.resolve(DivergenceResolution.FALSE_POSITIVE, "late", "second operator", clock.instant());

    assertThat(repository.update(stale)).isFalse();
    assertThat(divergences.get(stale.id()).status()).isEqualTo(DivergenceStatus.UNDER_REVIEW);
  }

  @Test
  void listingFiltersByStatusOriginAndMerchantAndPagesByCursor() {
    Payment first = newCharge(1000);
    Payment second = newCharge(2000);
    Payment third = newCharge(3000);
    divergences.open(first, "PAID", "one");
    clock.advance(Duration.ofSeconds(1));
    divergences.open(second, "PAID", "two");
    clock.advance(Duration.ofSeconds(1));
    divergences.open(third, "PAID", "three");
    divergences.openDispute(third, DisputeReason.OTHER, "a MERCHANT row the SYSTEM filter skips");

    DivergenceQuery firstPage =
        new DivergenceQuery(
            DivergenceStatus.OPEN, DivergenceOrigin.SYSTEM, null, merchant.value(), null, null, 2);
    List<ReconciliationDivergence> page = divergences.list(firstPage);

    assertThat(page)
        .extracting(ReconciliationDivergence::paymentId)
        .containsExactly(third.id(), second.id());

    DivergenceQuery secondPage =
        new DivergenceQuery(
            DivergenceStatus.OPEN,
            DivergenceOrigin.SYSTEM,
            null,
            merchant.value(),
            null,
            page.getLast().id(),
            2);
    assertThat(divergences.list(secondPage))
        .extracting(ReconciliationDivergence::paymentId)
        .containsExactly(first.id());

    DivergenceQuery disputes =
        new DivergenceQuery(
            null, DivergenceOrigin.MERCHANT, "DISPUTE", merchant.value(), null, null, 0);
    assertThat(divergences.list(disputes))
        .extracting(ReconciliationDivergence::paymentId)
        .containsExactly(third.id());
  }

  @Test
  void openCountsAreGroupedByOriginAndKind() {
    Payment payment = newCharge(1000);
    List<DivergenceCount> before = repository.countOpenByOriginAndKind();

    divergences.open(payment, "PAID", "one");
    divergences.open(payment, "UNCONFIRMED_WEBHOOK", "two");
    ReconciliationDivergence dispute = divergences.openDispute(payment, DisputeReason.OTHER, null);
    divergences.review(dispute.id(), "operator");

    List<DivergenceCount> after = repository.countOpenByOriginAndKind();

    assertThat(delta(before, after, DivergenceOrigin.SYSTEM, "PAID")).isEqualTo(1);
    assertThat(delta(before, after, DivergenceOrigin.SYSTEM, "UNCONFIRMED_WEBHOOK")).isEqualTo(1);
    // UNDER_REVIEW still counts: it is work in the operator's queue, not work done.
    assertThat(delta(before, after, DivergenceOrigin.MERCHANT, "DISPUTE")).isEqualTo(1);
  }

  /** A delta, not an absolute: the container is shared by every test class. */
  private static long delta(
      List<DivergenceCount> before,
      List<DivergenceCount> after,
      DivergenceOrigin origin,
      String kind) {
    return countOf(after, origin, kind) - countOf(before, origin, kind);
  }

  private static long countOf(List<DivergenceCount> counts, DivergenceOrigin origin, String kind) {
    return counts.stream()
        .filter(count -> count.origin() == origin && count.kind().equals(kind))
        .mapToLong(DivergenceCount::count)
        .sum();
  }
}
