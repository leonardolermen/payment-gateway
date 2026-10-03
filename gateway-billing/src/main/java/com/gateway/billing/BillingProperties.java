package com.gateway.billing;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables of billing. Every field has a default so the module runs with no {@code
 * gateway.billing.*} keys: a missing key must never become "retry forever" or "bill at midnight".
 *
 * @param dunningRetryDays days after a failed invoice on which to retry (spec §7); ascending
 * @param billingHour São Paulo hour at which a cycle is billed, after the day turns and outside the
 *     bank's boleto windows (spec §6.1)
 * @param cardRecurringEnabled whether a subscription may charge a stored card without a CVV; true
 *     by default; set false when the acquirer refuses token charges without a CVV (the sandbox
 *     could not prove either way, docs/providers/cielo/NOTES.md)
 * @param orderExpiryRecheck how often an expired order with a still-active attempt is looked at
 *     again: an attempt outlives the order's expiry by up to its own payment limit, so polling
 *     hourly is cheap and, unlike a retry, never runs out
 * @param attemptLock how long an order's attempt marker holds off a second attempt; it expires by
 *     itself so a process that dies mid-call never locks an order forever
 */
@ConfigurationProperties("gateway.billing")
public record BillingProperties(
    List<Integer> dunningRetryDays,
    int billingHour,
    Boolean cardRecurringEnabled,
    Duration orderExpiryRecheck,
    Duration attemptLock) {

  public BillingProperties {
    if (dunningRetryDays == null || dunningRetryDays.isEmpty()) {
      dunningRetryDays = List.of(1, 3, 7);
    }
    requireAscendingPositive(dunningRetryDays);
    if (billingHour <= 0 || billingHour > 23) {
      billingHour = 3;
    }
    if (cardRecurringEnabled == null) {
      cardRecurringEnabled = true;
    }
    if (orderExpiryRecheck == null
        || orderExpiryRecheck.isZero()
        || orderExpiryRecheck.isNegative()) {
      orderExpiryRecheck = Duration.ofHours(1);
    }
    if (attemptLock == null || attemptLock.isZero() || attemptLock.isNegative()) {
      attemptLock = Duration.ofMinutes(10);
    }
  }

  private static void requireAscendingPositive(List<Integer> days) {
    int previous = 0;
    for (Integer day : days) {
      if (day == null || day <= previous) {
        throw new IllegalArgumentException(
            "gateway.billing.dunning-retry-days must be ascending positive days");
      }
      previous = day;
    }
  }

  public static BillingProperties defaults() {
    return new BillingProperties(null, 0, null, null, null);
  }
}
