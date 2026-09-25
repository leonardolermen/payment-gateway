package com.gateway.providers.itau.boleto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** The bank's dates carry no zone; a boleto day is a São Paulo day (spec 2026-09-25, global constraint). */
public final class ItauDates {
  public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private ItauDates() {}

  /** {@code data_hora_inclusao_pagamento} first (ISO instant or ISO local date-time read as São Paulo); {@code data_inclusao_pagamento} as start of that day; null when neither parses. */
  public static Instant paidAt(String dateTime, String date) {
    if (dateTime != null) {
      try { return Instant.parse(dateTime); } catch (DateTimeParseException ignored) { /* not an instant */ }
      try { return LocalDateTime.parse(dateTime).atZone(SAO_PAULO).toInstant(); } catch (DateTimeParseException ignored) { /* not a local date-time either */ }
    }
    LocalDate d = date(date);
    return d == null ? null : d.atStartOfDay(SAO_PAULO).toInstant();
  }

  public static LocalDate date(String yyyyMmDd) {
    if (yyyyMmDd == null || yyyyMmDd.isBlank()) {
      return null;
    }
    try { return LocalDate.parse(yyyyMmDd); } catch (DateTimeParseException e) { return null; }
  }
}
