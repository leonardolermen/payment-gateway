package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BoletoDetailsJsonTest {
  @Test void roundTrips() {
    BoletoDetails b = new BoletoDetails("00000042", "550e8400-e29b-41d4-a716-446655440000", "3".repeat(47), "3".repeat(44), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PaidVia.BOLETO);
    assertThat(BoletoDetailsJson.read(BoletoDetailsJson.write(b))).isEqualTo(b);
  }

  @Test void nullsSurvive() {
    BoletoDetails b = new BoletoDetails("00000042", null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    assertThat(BoletoDetailsJson.read(BoletoDetailsJson.write(b))).isEqualTo(b);
    assertThat(BoletoDetailsJson.write(null)).isEqualTo("null");
    assertThat(BoletoDetailsJson.read("null")).isNull();
    assertThat(BoletoDetailsJson.read(null)).isNull();
  }

  /** Postgres reformats jsonb (a space after ':'); the reader must not depend on our own spacing. */
  @Test void readsPostgresSpacing() {
    String pg = "{\"dueDate\": \"2026-10-01\", \"paidVia\": null, \"nossoNumero\": \"00000042\", \"codigoBarras\": null, \"linhaDigitavel\": null, \"idBoletoIndividual\": null, \"paymentLimitDate\": \"2026-10-31\"}";
    BoletoDetails b = BoletoDetailsJson.read(pg);
    assertThat(b.nossoNumero()).isEqualTo("00000042");
    assertThat(b.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(b.paidVia()).isNull();
  }
}
