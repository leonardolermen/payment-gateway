package com.gateway.app.api.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.card.CardDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * One resource, one vocabulary: the REST response of a card payment and its webhook JSON have the
 * same keys, the card block included (spec §9), and neither carries card data.
 */
class CardPaymentJsonContractTest {
  final JsonMapper mapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  final Clock clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void restAndWebhookAgreeOnTheCardBlock() {
    Payment payment =
        Payment.createCard(
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "CIELO",
            Money.brl(12990),
            "order-42",
            "Pedido 42",
            null,
            CardDetails.requested(3, 0, "VISA", "3171", null),
            null,
            clock);
    payment.markCompletedByCard(
        new CardDetails("pid", "tid", "auth", "pos", "VISA", "3171", 3, null, "card-1", null, 0),
        Money.brl(12990),
        clock.instant(),
        EventSource.API);

    Map<String, Object> rest = mapper.convertValue(PaymentResponse.from(payment), Map.class);
    Map<String, Object> webhook = PaymentEvents.paymentJson(payment);

    assertThat(rest.keySet()).isEqualTo(webhook.keySet());
    assertThat(rest.get("pix")).isNull();
    assertThat(rest.get("boleto")).isNull();
    assertThat(((Map<String, Object>) rest.get("card")).keySet())
        .isEqualTo(((Map<String, Object>) webhook.get("card")).keySet());
    assertThat((Map<String, Object>) rest.get("card"))
        .containsEntry("last4", "3171")
        .containsEntry("card_id", "card-1")
        .containsEntry("authorization_code", "auth");
  }
}
