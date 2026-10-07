package com.gateway.billing.subscription;

/**
 * CANCELED is immediate; ENDED is the natural end of a cancel_at_period_end (spec §4.5). INCOMPLETE
 * is a card subscription created without a card, waiting for its first invoice to be paid by link;
 * INCOMPLETE_EXPIRED is that invoice expiring or being canceled unpaid (spec 2026-10-07 §2).
 */
public enum SubscriptionStatus {
  INCOMPLETE,
  INCOMPLETE_EXPIRED,
  ACTIVE,
  PAST_DUE,
  CANCELED,
  ENDED
}
