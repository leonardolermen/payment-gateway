package com.gateway.billing.subscription;

import com.gateway.billing.plan.PlanInterval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Cycle arithmetic in São Paulo time, like the Bolecode (spec §6.1). Month and year steps are
 * computed from the anchor day, not from the previous end, so a short month never shortens the ones
 * after it: 31 Jan → 28 Feb → 31 Mar, not 28 Mar.
 */
public final class BillingCalendar {
  static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private BillingCalendar() {}

  public static BillingPeriod firstPeriod(
      LocalDate start, PlanInterval interval, int count, int anchorDay) {
    return new BillingPeriod(start, advance(start, interval, count, anchorDay));
  }

  public static BillingPeriod next(
      BillingPeriod current, PlanInterval interval, int count, int anchorDay) {
    return new BillingPeriod(current.end(), advance(current.end(), interval, count, anchorDay));
  }

  private static LocalDate advance(
      LocalDate from, PlanInterval interval, int count, int anchorDay) {
    return switch (interval) {
      case DAY -> from.plusDays(count);
      case WEEK -> from.plusWeeks(count);
      case MONTH -> onAnchor(YearMonth.from(from).plusMonths(count), anchorDay);
      case YEAR -> onAnchor(YearMonth.from(from).plusYears(count), anchorDay);
    };
  }

  private static LocalDate onAnchor(YearMonth month, int anchorDay) {
    return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
  }

  public static Instant billingInstant(LocalDate day, int hour) {
    return day.atTime(LocalTime.of(hour, 0)).atZone(SAO_PAULO).toInstant();
  }

  public static Instant endOfDay(LocalDate day) {
    return day.atTime(LocalTime.of(23, 59, 59)).atZone(SAO_PAULO).toInstant();
  }

  public static LocalDate today(Instant now) {
    return now.atZone(SAO_PAULO).toLocalDate();
  }
}
