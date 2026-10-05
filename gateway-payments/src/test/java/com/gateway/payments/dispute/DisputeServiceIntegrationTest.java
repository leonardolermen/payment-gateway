package com.gateway.payments.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.reconciliation.DivergenceStatus;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DisputeServiceIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired DisputeService disputes;
  @Autowired Divergences divergences;
  @Autowired ReconciliationDivergenceRepository repository;

  @Test
  void openingADisputeStoresItOpenAndTellsTheMerchantInTheSameStroke() {
    Payment payment = newCharge(1000);

    Dispute dispute =
        disputes.open(merchant, payment.id(), DisputeReason.DUPLICATE, "charged twice");

    assertThat(dispute.status()).isEqualTo(DivergenceStatus.OPEN);
    assertThat(dispute.paymentId()).isEqualTo(payment.id());
    assertThat(dispute.reason()).isEqualTo(DisputeReason.DUPLICATE);
    assertThat(dispute.note()).isEqualTo("charged twice");
    assertThat(outboxTypes(dispute.id())).containsExactly("dispute.updated");
    assertThat(outboxPayload(dispute.id(), "dispute.updated")).contains("\"status\":\"OPEN\"");
  }

  @Test
  void aSecondDisputeWhileOneIsOpenIsRefusedAndEmitsNothing() {
    Payment payment = newCharge(1000);
    Dispute first = disputes.open(merchant, payment.id(), DisputeReason.NOT_SETTLED, null);

    assertThatThrownBy(() -> disputes.open(merchant, payment.id(), DisputeReason.OTHER, null))
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("DISPUTE_ALREADY_OPEN");

    assertThat(outboxTypes(first.id())).containsExactly("dispute.updated");
  }

  @Test
  void anotherMerchantsPaymentCannotBeDisputed() {
    Payment payment = newCharge(1000);

    assertThatThrownBy(
            () -> disputes.open(MerchantId.next(), payment.id(), DisputeReason.OTHER, null))
        .isInstanceOf(NotFoundException.class);
    assertThat(repository.findByPayment(payment.id())).isEmpty();
  }

  @Test
  void anotherMerchantsDisputeIsNotFound() {
    Payment payment = newCharge(1000);
    Dispute dispute = disputes.open(merchant, payment.id(), DisputeReason.OTHER, null);

    assertThatThrownBy(() -> disputes.get(MerchantId.next(), dispute.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(disputes.get(merchant, dispute.id()).id()).isEqualTo(dispute.id());
  }

  @Test
  void aSystemDivergenceOnTheMerchantsOwnPaymentIsNotFound() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");
    String systemId = repository.findByPayment(payment.id()).getFirst().id();

    assertThatThrownBy(() -> disputes.get(merchant, systemId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theListShowsOnlyTheMerchantsDisputesNeverSystemRows() {
    Payment disputed = newCharge(1000);
    Payment divergent = newCharge(2000);
    Dispute dispute = disputes.open(merchant, disputed.id(), DisputeReason.OTHER, null);
    divergences.open(divergent, "PAID", "bank says paid");

    DisputePage page = disputes.list(merchant, null, null, null, 20);

    assertThat(page.disputes()).extracting(Dispute::id).containsExactly(dispute.id());
    assertThat(page.nextCursor()).isNull();
    assertThat(disputes.list(MerchantId.next(), null, null, null, 20).disputes()).isEmpty();
  }

  @Test
  void aFullPageCarriesTheCursorToTheNext() {
    Dispute older = disputes.open(merchant, newCharge(1000).id(), DisputeReason.OTHER, null);
    Dispute newer = disputes.open(merchant, newCharge(1000).id(), DisputeReason.OTHER, null);

    DisputePage first = disputes.list(merchant, null, null, null, 1);
    DisputePage second = disputes.list(merchant, null, null, first.nextCursor(), 1);

    assertThat(first.disputes()).extracting(Dispute::id).containsExactly(newer.id());
    assertThat(second.disputes()).extracting(Dispute::id).containsExactly(older.id());
  }

  @Test
  void twoConcurrentOpensGiveOneDisputeAndOneRefusal() throws Exception {
    Payment payment = newCharge(1000);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> attempt =
        () -> {
          start.await();
          try {
            disputes.open(merchant, payment.id(), DisputeReason.DUPLICATE, "race");
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
    String disputeId = repository.findByPayment(payment.id()).getFirst().id();
    assertThat(repository.findByPayment(payment.id())).hasSize(1);
    assertThat(outboxTypes(disputeId)).containsExactly("dispute.updated");
  }
}
