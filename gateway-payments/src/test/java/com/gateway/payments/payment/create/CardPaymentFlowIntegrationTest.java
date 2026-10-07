package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.StuckCreatedSweep;
import com.gateway.payments.payment.card.CardDeclinedException;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec 2026-09-28 §6: every outcome of an authorization, and the card-data rule on the way. */
class CardPaymentFlowIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired StuckCreatedSweep sweep;

  int paymentRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.payments WHERE merchant_id = ?",
        Integer.class,
        merchant.value());
  }

  String details(Payment payment) {
    return jdbc.queryForObject(
        "SELECT details::text FROM payments.payments WHERE id = ?", String.class, payment.id());
  }

  @Test
  void anApprovedSaleWithCaptureIsCompletedAndStoresNoCardData() {
    Payment payment = newCard(12990, APPROVES);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.provider()).isEqualTo("CIELO");
    assertThat(payment.paidAmount()).isEqualTo(Money.brl(12990));
    assertThat(payment.card().paymentId()).isNotNull();
    assertThat(payment.card().last4()).isEqualTo("3171");
    assertThat(outboxTypes(payment.id())).containsExactly("payment.completed");
    assertThat(cards.lastIssued().merchantOrderId()).isEqualTo(payment.id());
    assertThat(details(payment)).doesNotContain(APPROVES).doesNotContain("\"123\"");
  }

  @Test
  void captureFalseStopsAtAuthorized() {
    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), false), 3, false));

    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment.paidAmount()).isNull();
    assertThat(payment.card().installments()).isEqualTo(3);
    assertThat(cards.lastIssued().capture()).isFalse();
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized");
  }

  /**
   * Spec 2026-10-07 §4: billing prices the installments; the flow charges the amount it is given
   * and records how much of it is interest, also on the row read back.
   */
  @Test
  void theInterestInsideTheAmountIsRecorded() {
    CreateCardPayment priced =
        new CreateCardPayment(
            merchant,
            com.gateway.kernel.provider.ProviderEnvironment.TEST,
            Money.brl(11076),
            "order-1",
            "Pedido 1",
            new CardChoice.NewCard(card(APPROVES), false),
            6,
            1076L,
            null,
            null,
            new CardCustomerData("Joao da Silva", "12345678901", "joao@example.com"),
            null);

    Payment payment = paymentService.create(priced);

    assertThat(cards.lastIssued().amount()).isEqualTo(Money.brl(11076));
    assertThat(payment.card().interestAmount()).isEqualTo(1076);
    assertThat(paymentQueries.get(merchant, payment.id()).card().interestAmount()).isEqualTo(1076);
    assertThat(details(payment)).contains("\"interestAmount\": 1076");
  }

  /** A decline is a result (spec §11): FAILED with our code, a 402 to the merchant, no retry. */
  @Test
  void aDeclineFailsWithTheDeclineCode() {
    assertThatThrownBy(() -> newCard(12990, INSUFFICIENT_FUNDS))
        .isInstanceOf(CardDeclinedException.class)
        .satisfies(
            thrown -> {
              CardDeclinedException declined = (CardDeclinedException) thrown;
              assertThat(declined.code()).isEqualTo("CARD_DECLINED");
              assertThat(declined.declineCode()).isEqualTo("INSUFFICIENT_FUNDS");
              Payment failed = paymentQueries.get(merchant, declined.paymentId());
              assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
              assertThat(failed.card().declineCode()).isEqualTo("INSUFFICIENT_FUNDS");
              assertThat(outboxTypes(failed.id())).containsExactly("payment.failed");
              assertThat(cards.callsFor(failed.id())).containsExactly("authorize:" + failed.id());
            });
  }

  @Test
  void aTimeoutThatLandedIsAdoptedFromTheQuery() {
    cards.landNextAuthorizeThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo POST timed out", null));

    Payment payment = newCard(12990, APPROVES);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(cards.callsFor(payment.id())).contains("findByOrder:" + payment.id());
  }

  /** Spec §6.4: no transaction at the Cielo after a timeout is FAILED, unlike the boleto. */
  @Test
  void aTimeoutWithNothingAtTheCieloFails() {
    cards.failNextAuthorizeWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo POST timed out", null));

    assertThatThrownBy(() -> newCard(12990, APPROVES))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments.payments WHERE merchant_id = ?",
                String.class,
                merchant.value()))
        .isEqualTo("FAILED");
  }

  /** Review Focus: a 201 that is not an answer (Status 12, 0, 14) is asked again, never adopted. */
  @Test
  void anInDoubtAnswerIsAskedAgainAndAdoptedWhenTheCieloDecided() {
    cards.nextAuthorizeStatus(CardStatus.PENDING);
    cards.nextFindByOrderStatus(CardStatus.PAID);

    Payment payment = newCard(12990, APPROVES);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(cards.callsFor(payment.id()))
        .containsExactly("authorize:" + payment.id(), "findByOrder:" + payment.id());
  }

  @Test
  void anInDoubtAnswerThatStaysInDoubtFails() {
    cards.nextAuthorizeStatus(CardStatus.NOT_FINISHED);

    assertThatThrownBy(() -> newCard(12990, APPROVES))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments.payments WHERE merchant_id = ?",
                String.class,
                merchant.value()))
        .isEqualTo("FAILED");
  }

  /**
   * The query itself failing proves nothing about the sale: the payment stays CREATED and the
   * stuck-CREATED sweep asks again after stuckCreatedAfter (plan decision in Task 8).
   */
  @Test
  void aFailedQueryLeavesItCreatedForTheSweeper() {
    cards.landNextAuthorizeThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo POST timed out", null));
    cards.failNextFindByOrderWith(
        new ProviderException(ProviderException.Code.UNAVAILABLE, "Cielo GET failed", null));

    assertThatThrownBy(() -> newCard(12990, APPROVES))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    String id =
        jdbc.queryForObject(
            "SELECT id FROM payments.payments WHERE merchant_id = ?",
            String.class,
            merchant.value());
    assertThat(paymentQueries.get(merchant, id).status()).isEqualTo(PaymentStatus.CREATED);

    clock.advance(Duration.ofMinutes(11));
    sweep.sweepStuckCreated(clock.instant());

    assertThat(paymentQueries.get(merchant, id).status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(outboxTypes(id)).containsExactly("payment.completed");
  }

  @Test
  void saveCardWithATokenStoresTheCardInTheSameTransaction() {
    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.card().cardId()).isNotNull();
    assertThat(cards.lastIssued().saveCard()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT last4 FROM payments.cards WHERE id = ? AND merchant_id = ?",
                String.class,
                payment.card().cardId(),
                merchant.value()))
        .isEqualTo("3171");
  }

  /** Spec §6.5: never fail an approved payment because the token did not come back. */
  @Test
  void saveCardWithoutATokenStillCompletesWithNoCardId() {
    cards.withholdNextToken();

    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.card().cardId()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.cards WHERE merchant_id = ?",
                Integer.class,
                merchant.value()))
        .isZero();
  }

  /** Spec §6.6: a stored card goes as a token, marked Used. */
  @Test
  void aSavedCardIsChargedByItsToken() {
    Payment first =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    Payment second =
        paymentService.create(
            cardCommand(
                5000,
                new CardChoice.SavedCardChoice(first.card().cardId(), Secret.of("123")),
                null,
                null));

    assertThat(second.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(second.card().cardId()).isEqualTo(first.card().cardId());
    assertThat(second.card().last4()).isEqualTo("3171");
    CardToken token = (CardToken) cards.lastIssued().source();
    assertThat(token.usage()).isEqualTo(CardOnFileUsage.USED);
    assertThat(cards.lastIssued().saveCard()).isFalse();
  }

  /** Spec §4: another merchant's card_id is CARD_NOT_FOUND, and nothing is written. */
  @Test
  void anotherMerchantsCardIdIsNotFoundBeforeARowExists() {
    MerchantId owner = merchant;
    Payment ownersPayment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));
    merchant = MerchantId.next();

    assertThatThrownBy(
            () ->
                paymentService.create(
                    cardCommand(
                        5000,
                        new CardChoice.SavedCardChoice(
                            ownersPayment.card().cardId(), Secret.of("123")),
                        null,
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CARD_NOT_FOUND");
    assertThat(paymentRows()).isZero();
    merchant = owner;
  }

  /** Review Focus 4. */
  @Test
  void installmentsBelowTheMinimumAreRefusedBeforeARowExists() {
    assertThatThrownBy(
            () ->
                paymentService.create(
                    cardCommand(1000, new CardChoice.NewCard(card(APPROVES), false), 3, null)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("INVALID_INSTALLMENTS");
    assertThat(paymentRows()).isZero();

    Payment inThree =
        paymentService.create(
            cardCommand(1600, new CardChoice.NewCard(card(APPROVES), false), 3, null));
    assertThat(inThree.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(cards.lastIssued().installments().count()).isEqualTo(3);
  }

  @Test
  void aCustomerWithoutANameIsRefusedBeforeARowExists() {
    CreateCardPayment noName =
        new CreateCardPayment(
            merchant,
            com.gateway.kernel.provider.ProviderEnvironment.TEST,
            Money.brl(100),
            null,
            null,
            new CardChoice.NewCard(card(APPROVES), false),
            null,
            null,
            null,
            null,
            new CardCustomerData(" ", null, null),
            null);

    assertThatThrownBy(() -> paymentService.create(noName))
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("CUSTOMER_REQUIRED");
              assertThat(thrown.getMessage()).isEqualTo("customer.name is required");
            });
    assertThat(paymentRows()).isZero();
  }

  @Test
  void aBadSoftDescriptorIsRefusedBeforeARowExists() {
    CreateCardPayment longDescriptor =
        new CreateCardPayment(
            merchant,
            com.gateway.kernel.provider.ProviderEnvironment.TEST,
            Money.brl(100),
            null,
            null,
            new CardChoice.NewCard(card(APPROVES), false),
            null,
            null,
            null,
            "LOJA-42 PEDIDO",
            new CardCustomerData("Joao", null, null),
            null);

    assertThatThrownBy(() -> paymentService.create(longDescriptor))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("INVALID_SOFT_DESCRIPTOR");
    assertThat(paymentRows()).isZero();
  }

  @Test
  void theEventLogOfACardPaymentCarriesNoCardData() {
    Payment payment = newCard(12990, APPROVES);

    List<String> payloads =
        jdbc.queryForList(
            "SELECT payload::text FROM payments.payment_events WHERE payment_id = ?",
            String.class,
            payment.id());

    assertThat(payloads)
        .isNotEmpty()
        .allSatisfy(payload -> assertThat(payload).doesNotContain(APPROVES));
  }
}
