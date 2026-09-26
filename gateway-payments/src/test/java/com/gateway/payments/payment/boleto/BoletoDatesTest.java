package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * A boleto day is a São Paulo day: at 01:00Z on the 2nd it is still the 1st in São Paulo, and the
 * 1st ends at 02:59:59Z on the 2nd.
 */
class BoletoDatesTest {
  @Test
  void todayAndEndOfDayAreSaoPaulo() {
    Clock c = Clock.fixed(Instant.parse("2026-10-02T01:00:00Z"), ZoneOffset.UTC);
    assertThat(BoletoDates.today(c)).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(BoletoDates.endOfDay(LocalDate.of(2026, 10, 1)))
        .isEqualTo(Instant.parse("2026-10-02T02:59:59Z"));
  }
}
