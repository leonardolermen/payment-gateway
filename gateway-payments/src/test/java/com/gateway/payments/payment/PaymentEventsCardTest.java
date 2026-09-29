package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.card.CardDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The public JSON of a card payment: the card block of spec §9, pix null, no card data. */
class PaymentEventsCardTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void aCapturedCardPaymentShowsTheCardBlock() {
    Payment payment =
        Payment.createCard(
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "CIELO",
            Money.brl(12990),
            "order-42",
            "Pedido 42",
            null,
            CardDetails.requested(3, "VISA", "3171", null),
            clock);
    payment.markCompletedByCard(
        new CardDetails("pid", "tid-1", "auth-1", "pos-1", "VISA", "3171", 3, null, "card-1", null),
        Money.brl(12990),
        clock.instant(),
        EventSource.API);

    Map<String, Object> json = PaymentEvents.paymentJson(payment);

    assertThat(json.get("pix")).isNull();
    assertThat(json.get("boleto")).isNull();
    Map<String, Object> card = (Map<String, Object>) json.get("card");
    assertThat(card)
        .containsOnlyKeys(
            "brand",
            "last4",
            "installments",
            "authorization_code",
            "tid",
            "captured_amount",
            "card_id");
    assertThat(card)
        .containsEntry("brand", "VISA")
        .containsEntry("last4", "3171")
        .containsEntry("installments", 3)
        .containsEntry("authorization_code", "auth-1")
        .containsEntry("tid", "tid-1")
        .containsEntry("captured_amount", 12990L)
        .containsEntry("card_id", "card-1");
  }

  @Test
  void aPixPaymentHasANullCardBlock() {
    Payment pix =
        Payment.create(
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "ITAU",
            Money.brl(100),
            null,
            null,
            null,
            3600,
            clock);

    assertThat(PaymentEvents.paymentJson(pix)).containsEntry("card", null);
  }
}
