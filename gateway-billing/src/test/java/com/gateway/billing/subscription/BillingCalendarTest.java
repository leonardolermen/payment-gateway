package com.gateway.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.plan.PlanInterval;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BillingCalendarTest {

  @Test
  void aMonthlyPeriodStartedOnThe31stShortensInFebruaryAndReturnsInMarch() {
    BillingPeriod january =
        BillingCalendar.firstPeriod(LocalDate.of(2026, 1, 31), PlanInterval.MONTH, 1, 31);
    BillingPeriod february = BillingCalendar.next(january, PlanInterval.MONTH, 1, 31);
    BillingPeriod march = BillingCalendar.next(february, PlanInterval.MONTH, 1, 31);

    assertThat(january.end()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(february)
        .isEqualTo(new BillingPeriod(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)));
    assertThat(march)
        .isEqualTo(new BillingPeriod(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)));
  }

  @Test
  void aYearlyPeriodOnFebruary29thFallsBackToThe28th() {
    BillingPeriod first =
        BillingCalendar.firstPeriod(LocalDate.of(2028, 2, 29), PlanInterval.YEAR, 1, 29);

    assertThat(first.end()).isEqualTo(LocalDate.of(2029, 2, 28));
  }

  @Test
  void weeksAndDaysAddPlainly() {
    assertThat(
            BillingCalendar.firstPeriod(LocalDate.of(2026, 10, 2), PlanInterval.WEEK, 2, 2).end())
        .isEqualTo(LocalDate.of(2026, 10, 16));
    assertThat(
            BillingCalendar.firstPeriod(LocalDate.of(2026, 10, 2), PlanInterval.DAY, 10, 2).end())
        .isEqualTo(LocalDate.of(2026, 10, 12));
  }

  @Test
  void billingInstantIsTheConfiguredSaoPauloHour() {
    assertThat(BillingCalendar.billingInstant(LocalDate.of(2026, 10, 2), 3))
        .isEqualTo(java.time.Instant.parse("2026-10-02T06:00:00Z"));
    assertThat(BillingCalendar.endOfDay(LocalDate.of(2026, 10, 2)))
        .isEqualTo(java.time.Instant.parse("2026-10-03T02:59:59Z"));
  }
}
