package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.BillingProperties;
import com.gateway.billing.subscription.BillingCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DunningScheduleTest {
  private static final Instant FAILED_AT = Instant.parse("2026-10-02T15:00:00Z");

  private final DunningSchedule schedule =
      new DunningSchedule(new BillingProperties(List.of(1, 3, 7), 3, null, null, null));

  @Test
  void theFirstRetryIsTheNextDayAtTheBillingHour() {
    assertThat(schedule.nextAfter(0, FAILED_AT))
        .contains(BillingCalendar.billingInstant(LocalDate.of(2026, 10, 3), 3));
  }

  @Test
  void theThirdRetryIsSevenDaysAfterTheFailure() {
    assertThat(schedule.nextAfter(2, FAILED_AT))
        .contains(BillingCalendar.billingInstant(LocalDate.of(2026, 10, 9), 3));
  }

  @Test
  void thereIsNothingAfterTheLastConfiguredDay() {
    assertThat(schedule.nextAfter(3, FAILED_AT)).isEmpty();
  }
}
