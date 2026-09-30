package com.gateway.providers.cielo.sale;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * The Cielo writes dates without a zone: "2025-11-24 18:04:07" in a sale, "2024-11-29T13:36:04.033"
 * in the by-order query. Both are Brasília time; one converter so the zone is decided once.
 */
public final class CieloDates {
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
  private static final DateTimeFormatter SPACE_OR_T =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd")
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .optionalStart()
          .appendLiteral('T')
          .optionalEnd()
          .appendPattern("HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .optionalEnd()
          .toFormatter();

  private CieloDates() {}

  public static Instant parse(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }

    return LocalDateTime.parse(text.trim(), SPACE_OR_T).atZone(SAO_PAULO).toInstant();
  }
}
