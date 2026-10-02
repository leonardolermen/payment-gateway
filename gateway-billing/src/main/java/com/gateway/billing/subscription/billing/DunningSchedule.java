package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingProperties;
import com.gateway.billing.subscription.BillingCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * When the next retry of a failed invoice runs (spec §7). Days count from the failure that is being
 * retried, not from the first one, and land at the billing hour: outside the bank's boleto windows,
 * like the cycle itself.
 */
public class DunningSchedule {
  private final BillingProperties properties;

  public DunningSchedule(BillingProperties properties) {
    this.properties = properties;
  }

  /** Empty once every configured retry day has been used: the invoice is exhausted. */
  public Optional<Instant> nextAfter(int attemptsSoFar, Instant failedAt) {
    List<Integer> days = properties.dunningRetryDays();
    if (attemptsSoFar >= days.size()) {
      return Optional.empty();
    }

    LocalDate day = BillingCalendar.today(failedAt).plusDays(days.get(attemptsSoFar));

    return Optional.of(BillingCalendar.billingInstant(day, properties.billingHour()));
  }
}
