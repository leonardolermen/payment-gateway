package com.gateway.payments.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec §8: the body is a hint — PaymentId and ChangeType — and the query of that PaymentId is the
 * truth. docs/webhook, "Tabela de ChangeType".
 */
class CardNotificationsIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired WebhookInboxService inbox;

  Payment authorized() {
    return paymentService.create(
        cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));
  }

  String notify(MerchantId to, String cieloPaymentId, int changeType) {
    String body = "{\"PaymentId\":\"" + cieloPaymentId + "\",\"ChangeType\":" + changeType + "}";
    String id = inbox.accept("CIELO", to, "{}", body.getBytes(StandardCharsets.UTF_8));
    inbox.process(id);
    return id;
  }

  String inboxStatus(String id) {
    return jdbc.queryForObject(
        "SELECT status FROM payments.webhook_inbox WHERE id = ?", String.class, id);
  }

  List<String> divergences(Payment payment) {
    return jdbc.queryForList(
        "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
        String.class,
        payment.id());
  }

  @Test
  void aCaptureDoneOutsideTheGatewayCompletesTheAuthorization() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    String id = notify(merchant, payment.card().paymentId(), 1);

    Payment after = paymentQueries.get(merchant, payment.id());
    assertThat(after.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(outboxTypes(payment.id()))
        .containsExactly("payment.authorized", "payment.completed");
    assertThat(inboxStatus(id)).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM payments.payment_events WHERE payment_id = ? AND type = 'completed'",
                String.class,
                payment.id()))
        .isEqualTo("PROVIDER_WEBHOOK");
  }

  @Test
  void aVoidDoneOutsideTheGatewayCancelsTheAuthorization() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.VOIDED);

    notify(merchant, payment.card().paymentId(), 1);

    assertThat(paymentQueries.get(merchant, payment.id()).status())
        .isEqualTo(PaymentStatus.CANCELED);
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized", "payment.canceled");
  }

  /** ChangeType 25 on a captured payment the gateway did not refund: a human looks. */
  @Test
  void aRefundDoneOutsideTheGatewayOpensADivergence() {
    Payment payment = newCard(10000, APPROVES);
    cards.setStatus(payment.card().paymentId(), CardStatus.REFUNDED);

    notify(merchant, payment.card().paymentId(), 25);

    assertThat(paymentQueries.get(merchant, payment.id()).status())
        .isEqualTo(PaymentStatus.COMPLETED);
    assertThat(divergences(payment)).containsExactly("REFUNDED_AT_PROVIDER");
  }

  @Test
  void aDeniedCancelAndAFraudAlertAreDivergences() {
    Payment payment = newCard(10000, APPROVES);

    notify(merchant, payment.card().paymentId(), 5);
    notify(merchant, payment.card().paymentId(), 8);

    assertThat(divergences(payment)).containsExactlyInAnyOrder("VOID_DENIED", "FRAUD_ALERT");
  }

  /** 2, 3, 4, 6, 7 are not this phase's (plan D14): a stored fact, no transition. */
  @Test
  void aRecurrenceNotificationIsRecordedAsIgnored() {
    Payment payment = newCard(10000, APPROVES);

    String id = notify(merchant, payment.card().paymentId(), 2);

    assertThat(inboxStatus(id)).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForList(
                "SELECT type FROM payments.payment_events WHERE payment_id = ? ORDER BY sequence",
                String.class,
                payment.id()))
        .endsWith("ignored");
  }

  /** Review Focus 5: another system on the same Cielo store, or a sale from before the gateway. */
  @Test
  void anUnknownPaymentIdIsIgnored() {
    String id = notify(merchant, "2352fc91-f9a4-4ca2-aedb-31488b9658c9", 1);

    assertThat(inboxStatus(id)).isEqualTo("IGNORED");
  }

  /** The URL's merchant scopes the lookup: another merchant's PaymentId is not reachable. */
  @Test
  void anotherMerchantsPaymentIdIsIgnored() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    String id = notify(MerchantId.next(), payment.card().paymentId(), 1);

    assertThat(inboxStatus(id)).isEqualTo("IGNORED");
    assertThat(paymentQueries.get(merchant, payment.id()).status())
        .isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void anUnreadableBodyFailsWithoutRetrying() {
    String id =
        inbox.accept(
            "CIELO", merchant, "{}", "{\"ChangeType\":1}".getBytes(StandardCharsets.UTF_8));

    inbox.process(id);

    assertThat(inboxStatus(id)).isEqualTo("FAILED");
  }
}
