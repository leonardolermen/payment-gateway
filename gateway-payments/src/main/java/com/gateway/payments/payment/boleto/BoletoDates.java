package com.gateway.payments.payment.boleto;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Boleto dates are calendar days at the bank, which lives in São Paulo; the gateway's clock is UTC,
 * so every conversion goes through here.
 */
public final class BoletoDates {
  public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private BoletoDates() {}

  public static LocalDate today(Clock clock) {
    return LocalDate.ofInstant(clock.instant(), SAO_PAULO);
  }

  /** 23:59:59 of that day in São Paulo: the last instant the bank still takes the payment. */
  public static Instant endOfDay(LocalDate day) {
    return day.atTime(23, 59, 59).atZone(SAO_PAULO).toInstant();
  }
}
