package com.gateway.payments.service;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.RefundResult;
import com.gateway.kernel.provider.RefundStatus;
import com.gateway.payments.domain.EventSource;
import com.gateway.payments.domain.JobType;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.Refund;
import com.gateway.payments.domain.RefundState;
import com.gateway.payments.repository.JobRepository;
import com.gateway.payments.repository.PaymentRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefundServiceIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired RefundService refunds;
  @Autowired RefundPollingService polling;
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired PlatformTransactionManager txManager;

  @BeforeEach
  void bankAnswersProcessing() {
    bank.nextRefundStatus(RefundStatus.PROCESSING);
  }

  Payment paid(long cents) {
    Payment p = newCharge(cents);
    return new TransactionTemplate(txManager).execute(s -> {
      Payment loaded = payments.findById(p.id()).orElseThrow();
      return payments.save(
          loaded, List.of(loaded.markCompleted("E2E" + p.id(), Money.brl(cents), clock.instant(), EventSource.PROVIDER_WEBHOOK)));
    });
  }

  @Test
  void requestOnCompletedPaymentIsProcessingAndEnqueuesPolling() {
    Payment p = paid(1000);

    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
    assertThat(jobs.findByTypeAndRef(JobType.POLL_REFUND, r.id())).isPresent();
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested");
    assertThat(outboxPayload(r.id(), "refund.requested")).contains("\"payment_id\":\"" + p.id() + "\"").contains("\"state\":\"processing\"");
    assertThat(refunds.list(merchant, p.id())).extracting(Refund::id).containsExactly(r.id());
  }

  @Test
  void nullAmountRefundsWhatIsLeft() {
    Payment p = paid(1000);
    refunds.request(merchant, p.id(), Money.brl(300));

    Refund rest = refunds.request(merchant, p.id(), null);

    assertThat(rest.amount()).isEqualTo(Money.brl(700));
  }

  @Test
  void sumAboveAmountIsRefused() {
    Payment p = paid(1000);
    refunds.request(merchant, p.id(), Money.brl(600));

    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(500)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }

  @Test
  void pendingPaymentCannotBeRefunded() {
    Payment p = newCharge(1000);
    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(1)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void afterNinetyDaysIsRefused() {
    Payment p = paid(1000);
    clock.advance(Duration.ofDays(91));

    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(100)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("REFUND_WINDOW_CLOSED");
  }

  @Test
  void providerUpdateCompletesAndProjects() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    refunds.applyProviderUpdate(new RefundResult(r.id(), RefundStatus.COMPLETED, Money.brl(400), null, clock.instant(), clock.instant()));

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.COMPLETED);
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(400));
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested", "refund.completed");

    // A second notification of the same settlement is a no-op, not a double refund.
    refunds.applyProviderUpdate(new RefundResult(r.id(), RefundStatus.COMPLETED, Money.brl(400), null, clock.instant(), clock.instant()));
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(400));
  }

  @Test
  void providerUpdateFailedKeepsPaymentUntouched() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    refunds.applyProviderUpdate(new RefundResult(r.id(), RefundStatus.FAILED, Money.brl(400), "saldo insuficiente", clock.instant(), null));

    Refund failed = refunds.get(merchant, r.id());
    assertThat(failed.state()).isEqualTo(RefundState.FAILED);
    assertThat(failed.failureReason()).isEqualTo("saldo insuficiente");
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(0));
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested", "refund.failed");
    // A failed refund frees its amount again.
    assertThat(refunds.request(merchant, p.id(), Money.brl(1000)).state()).isEqualTo(RefundState.PROCESSING);
  }

  @Test
  void pollingClosesTheRefundWhenTheBankSettles() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    assertThat(polling.poll(r.id())).isFalse();
    bank.refundResult(new RefundResult(r.id(), RefundStatus.COMPLETED, Money.brl(400), null, clock.instant(), clock.instant()));
    assertThat(polling.poll(r.id())).isTrue();

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.COMPLETED);
  }
}
