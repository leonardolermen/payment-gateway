package com.gateway.app.api.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * One resource, one vocabulary: the payment a merchant GETs from the REST API and the one a {@code
 * payment.*} webhook carries are built by two different pieces of code (PaymentResponse serialized
 * by the app's mapper, PaymentEvents' hand-built map). Nothing tied their key sets together, so a
 * field added to one silently went missing from the other. Now with the boleto block.
 */
class PaymentJsonContractTest {
  /** Same naming as application.yml's spring.jackson.property-naming-strategy. */
  private final JsonMapper appMapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  private final Instant now = Instant.parse("2026-09-24T12:00:00Z");

  private Payment pix() {
    return Payment.rehydrate(
        "01J00000000000000000000000",
        MerchantId.next(),
        ProviderEnvironment.TEST,
        "ITAU",
        PaymentMethod.PIX,
        PaymentStatus.COMPLETED,
        Money.brl(15990),
        "order-42",
        "Pedido 42",
        "hash",
        new PixDetails("01J00000000000000000000000", "000201...", "pix.example/qr/1", "E123"),
        null,
        now.plusSeconds(3600),
        now,
        Money.brl(15990),
        Money.brl(0),
        3,
        now,
        now,
        Clock.systemUTC());
  }

  private Payment bolecode() {
    return Payment.rehydrate(
        "01J00000000000000000000001",
        MerchantId.next(),
        ProviderEnvironment.TEST,
        "ITAU",
        PaymentMethod.BOLECODE,
        PaymentStatus.COMPLETED,
        Money.brl(12990),
        "order-43",
        "Pedido 43",
        "hash",
        new PixDetails("BL15000005206109000000000000001", "000201...", null, null),
        new BoletoDetails(
            "00000001",
            "uuid",
            "1".repeat(47),
            "1".repeat(44),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            PaidVia.BOLETO),
        Instant.parse("2026-11-01T02:59:59Z"),
        now,
        Money.brl(12990),
        Money.brl(0),
        3,
        now,
        now,
        Clock.systemUTC());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rest(Payment p) {
    return appMapper.readValue(
        appMapper.writeValueAsString(PaymentResponse.from(p)),
        new TypeReference<Map<String, Object>>() {});
  }

  @Test
  void restAndWebhookPaymentsHaveTheSameKeysForBothMethods() {
    for (Payment p : new Payment[] {pix(), bolecode()}) {
      Map<String, Object> rest = rest(p);
      Map<String, Object> webhook = PaymentEvents.paymentJson(p);
      assertThat(rest.keySet())
          .as(p.method().name())
          .containsExactlyInAnyOrderElementsOf(webhook.keySet());
      assertThat(Set.copyOf(((Map<?, ?>) rest.get("pix")).keySet()))
          .isEqualTo(Set.copyOf(((Map<?, ?>) webhook.get("pix")).keySet()));
      assertThat(rest.keySet()).doesNotContain("customer_document_hash");
      assertThat(rest).containsEntry("method", p.method().name());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void theBoletoBlockIsNullForPixAndFullForBolecode() {
    assertThat(rest(pix())).containsEntry("boleto", null);
    assertThat(PaymentEvents.paymentJson(pix())).containsEntry("boleto", null);
    Map<String, Object> rest = (Map<String, Object>) rest(bolecode()).get("boleto");
    Map<String, Object> webhook =
        (Map<String, Object>) PaymentEvents.paymentJson(bolecode()).get("boleto");
    assertThat(rest.keySet())
        .containsExactlyInAnyOrder(
            "linha_digitavel", "codigo_barras", "due_date", "payment_limit_date", "paid_via");
    assertThat(Set.copyOf(rest.keySet())).isEqualTo(Set.copyOf(webhook.keySet()));
    assertThat(rest)
        .containsEntry("paid_via", "BOLETO")
        .containsEntry("due_date", "2026-10-01")
        .containsEntry("payment_limit_date", "2026-10-31");
    assertThat(webhook).containsEntry("paid_via", "BOLETO").containsEntry("due_date", "2026-10-01");
  }
}
