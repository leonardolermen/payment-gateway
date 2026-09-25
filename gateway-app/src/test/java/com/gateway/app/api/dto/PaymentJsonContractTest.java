package com.gateway.app.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.payment.PaymentEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * One resource, one vocabulary: the payment a merchant GETs from the REST API and the one a
 * {@code payment.*} webhook carries are built by two different pieces of code (PaymentResponse
 * serialized by the app's mapper, PaymentEvents' hand-built map). Nothing tied their key sets
 * together, so a field added to one silently went missing from the other.
 */
class PaymentJsonContractTest {
  /** Same naming as application.yml's spring.jackson.property-naming-strategy. */
  private final JsonMapper appMapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  @Test
  void restAndWebhookPaymentsHaveTheSameKeys() {
    Instant now = Instant.parse("2026-09-24T12:00:00Z");
    Payment p =
        Payment.rehydrate(
            "01J00000000000000000000000",
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "ITAU",
            PaymentStatus.COMPLETED,
            Money.brl(15990),
            "order-42",
            "Pedido 42",
            "hash",
            new PixDetails("01J00000000000000000000000", "000201...", "pix.example/qr/1", "E123"),
            now.plusSeconds(3600),
            now,
            Money.brl(15990),
            Money.brl(0),
            3,
            now,
            now,
            Clock.systemUTC());

    Map<String, Object> rest = appMapper.readValue(appMapper.writeValueAsString(PaymentResponse.from(p)), new TypeReference<>() {});
    Map<String, Object> webhook = PaymentEvents.paymentJson(p);

    assertThat(rest.keySet()).containsExactlyInAnyOrderElementsOf(webhook.keySet());
    assertThat(Set.copyOf(((Map<?, ?>) rest.get("pix")).keySet())).isEqualTo(Set.copyOf(((Map<?, ?>) webhook.get("pix")).keySet()));
    assertThat(rest.keySet()).doesNotContain("customer_document_hash");
  }
}
