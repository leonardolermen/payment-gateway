package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The bank gives a date, sometimes a date-time, never a zone: every boleto date is a São Paulo day. */
class ItauDatesTest {
  @Test void dateOnlyIsStartOfTheSaoPauloDay() {
    assertThat(ItauDates.paidAt(null, "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T03:00:00Z"));
  }

  @Test void dateTimeWinsAndIsReadAsSaoPaulo() {
    assertThat(ItauDates.paidAt("2020-01-20T14:30:00", "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T17:30:00Z"));
    assertThat(ItauDates.paidAt("2020-01-20T14:30:00Z", "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T14:30:00Z"));
    assertThat(ItauDates.paidAt("garbage", "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T03:00:00Z"));
  }

  @Test void nullsStayNull() {
    assertThat(ItauDates.paidAt(null, null)).isNull();
    assertThat(ItauDates.date(null)).isNull();
    assertThat(ItauDates.date("2030-08-06")).isEqualTo(LocalDate.of(2030, 8, 6));
  }
}
