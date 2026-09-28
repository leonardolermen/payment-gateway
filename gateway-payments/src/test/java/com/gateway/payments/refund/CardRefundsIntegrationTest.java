package com.gateway.payments.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.card.CardCapture;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec §4: a card refund is the Cielo's void with an amount, synchronous — REQUESTED to COMPLETED
 * or FAILED in the request, no polling job; partial allowed; the sum capped by paid_amount.
 */
class CardRefundsIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired RefundService refunds;
  @Autowired CardCapture capture;

  @Test
  void aTotalRefundCompletesInTheRequest() {
    Payment payment = newCard(10000, APPROVES);

    Refund refund = refunds.request(merchant, payment.id(), null);

    assertThat(refund.state()).isEqualTo(RefundState.COMPLETED);
    assertThat(refund.amount()).isEqualTo(Money.brl(10000));
    assertThat(paymentQueries.get(merchant, payment.id()).refundedAmount())
        .isEqualTo(Money.brl(10000));
    assertThat(outboxTypes(refund.id())).containsExactly("refund.completed");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.jobs WHERE ref_id = ?", Integer.class, refund.id()))
        .isZero();
  }

  @Test
  void partialRefundsAddUpToThePaidAmountAndNoFurther() {
    Payment payment = newCard(10000, APPROVES);

    refunds.request(merchant, payment.id(), Money.brl(3000));
    refunds.request(merchant, payment.id(), Money.brl(7000));

    assertThat(paymentQueries.get(merchant, payment.id()).fullyRefunded()).isTrue();
    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(1)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }

  @Test
  void aPartialCaptureCapsTheRefund() {
    Payment payment =
        paymentService.create(
            cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));
    capture.capture(merchant, payment.id(), Money.brl(6000));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(6001)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
    assertThat(refunds.request(merchant, payment.id(), null).amount()).isEqualTo(Money.brl(6000));
  }

  @Test
  void anAuthorizationIsCanceledNotRefunded() {
    Payment payment =
        paymentService.create(
            cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), null))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void aRefusedRefundFailsAndFreesTheAmount() {
    Payment payment = newCard(10000, APPROVES);
    cards.failNextRefundWith(
        new ProviderException(
            ProviderException.Code.INVALID, 400, "312", "312 Transaction not available to refund"));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(4000)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_DECLINED");

    assertThat(refunds.list(merchant, payment.id()))
        .singleElement()
        .extracting(Refund::state)
        .isEqualTo(RefundState.FAILED);
    assertThat(refunds.request(merchant, payment.id(), null).amount()).isEqualTo(Money.brl(10000));
  }

  /**
   * Timeout is not failure (CLAUDE.md): the void may have landed, so the amount stays reserved, the
   * refund stays PROCESSING and a human sees REFUND_UNKNOWN (plan C11).
   */
  @Test
  void aTimeoutKeepsTheAmountReservedAndOpensADivergence() {
    Payment payment = newCard(10000, APPROVES);
    cards.failNextRefundWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo PUT timed out", null));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(4000)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");

    assertThat(refunds.list(merchant, payment.id()))
        .singleElement()
        .extracting(Refund::state)
        .isEqualTo(RefundState.PROCESSING);
    assertThat(
            jdbc.queryForList(
                "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
                String.class,
                payment.id()))
        .containsExactly("REFUND_UNKNOWN");
    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(6001)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }
}
