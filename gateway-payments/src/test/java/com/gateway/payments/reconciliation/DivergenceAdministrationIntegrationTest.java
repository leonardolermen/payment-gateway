package com.gateway.payments.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.payments.dispute.DisputeReason;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DivergenceAdministrationIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired DivergenceAdministration administration;
  @Autowired Divergences divergences;
  @Autowired ReconciliationDivergenceRepository repository;

  @Test
  void resolvingADisputeEmitsOneDisputeUpdatedWithTheNewStatus() {
    Payment payment = newCharge(1000);
    ReconciliationDivergence dispute =
        divergences.openDispute(payment, DisputeReason.DUPLICATE, "charged twice");

    administration.resolve(dispute.id(), DivergenceResolution.REJECTED, "one charge only", "admin");

    assertThat(outboxTypes(dispute.id())).containsExactly("dispute.updated");
    assertThat(outboxPayload(dispute.id(), "dispute.updated"))
        .contains("\"status\":\"REJECTED\"")
        .contains("\"payment_id\":\"" + payment.id() + "\"")
        .contains("\"resolution_note\":\"one charge only\"");
  }

  @Test
  void resolvingASystemDivergenceEmitsNothing() {
    Payment payment = newCharge(1000);
    divergences.open(payment, "PAID", "bank says paid");
    String id = repository.findByPayment(payment.id()).getFirst().id();

    administration.review(id, "admin");
    administration.resolve(id, DivergenceResolution.FALSE_POSITIVE, "bank echo", "admin");

    assertThat(outboxTypes(id)).isEmpty();
  }

  @Test
  void aSecondResolveIsRefusedAndEmitsNothingMore() {
    Payment payment = newCharge(1000);
    ReconciliationDivergence dispute =
        divergences.openDispute(payment, DisputeReason.NOT_SETTLED, null);
    administration.resolve(dispute.id(), DivergenceResolution.RESOLVED, "settled", "admin");

    assertThatThrownBy(
            () ->
                administration.resolve(
                    dispute.id(), DivergenceResolution.REJECTED, "again", "admin"))
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("DIVERGENCE_CLOSED");

    assertThat(outboxTypes(dispute.id())).containsExactly("dispute.updated");
  }

  @Test
  void theDetailCarriesThePayment() {
    Payment payment = newCharge(1000);
    ReconciliationDivergence dispute = divergences.openDispute(payment, DisputeReason.OTHER, null);

    DivergenceDetail detail = administration.detail(dispute.id());

    assertThat(detail.divergence().id()).isEqualTo(dispute.id());
    assertThat(detail.payment().merchantId()).isEqualTo(payment.merchantId());
  }

  @Test
  void anUnknownIdIsNotFound() {
    assertThatThrownBy(() -> divergences.get("missing")).isInstanceOf(NotFoundException.class);
  }
}
