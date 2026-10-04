package com.gateway.billing.subscription;

/** CANCELED is immediate; ENDED is the natural end of a cancel_at_period_end (spec §4.5). */
public enum SubscriptionStatus {
  ACTIVE,
  PAST_DUE,
  CANCELED,
  ENDED
}
