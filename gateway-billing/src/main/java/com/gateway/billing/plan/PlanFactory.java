package com.gateway.billing.plan;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import java.time.Clock;
import java.time.Instant;

public final class PlanFactory {
  private static final int MAX_NAME_LENGTH = 80;
  private static final int MAX_INTERVAL_COUNT = 12;
  private static final int MAX_TRIAL_DAYS = 365;

  private PlanFactory() {}

  /** Messages name the field as the client sent it ({@code interval_count}, not intervalCount). */
  public static Plan fromRequest(
      MerchantId merchantId,
      String name,
      Money amount,
      PlanInterval interval,
      Integer intervalCount,
      Integer trialDays,
      Clock clock) {
    String trimmedName = validName(name);

    if (amount == null || amount.cents() <= 0) {
      throw new IllegalArgumentException("amount must be greater than zero");
    }

    if (interval == null) {
      throw new IllegalArgumentException("interval is required");
    }

    int count = intervalCount == null ? 1 : intervalCount;

    if (count < 1 || count > MAX_INTERVAL_COUNT) {
      throw new IllegalArgumentException("interval_count must be between 1 and 12");
    }

    int trial = trialDays == null ? 0 : trialDays;

    if (trial < 0 || trial > MAX_TRIAL_DAYS) {
      throw new IllegalArgumentException("trial_days must be between 0 and 365");
    }

    Instant now = clock.instant();

    return new Plan(
        Ulid.next(), merchantId, trimmedName, amount, interval, count, trial, true, 1, now, now);
  }

  /** Shared with rename: a plan cannot be renamed to something it could not have been born as. */
  public static String validName(String name) {
    String trimmed = name == null ? "" : name.trim();

    if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("name must have between 1 and 80 characters");
    }

    return trimmed;
  }
}
