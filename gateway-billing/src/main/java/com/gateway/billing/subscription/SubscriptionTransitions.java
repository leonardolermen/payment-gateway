package com.gateway.billing.subscription;

import java.util.Set;

/**
 * The state machine as a table, like {@code OrderTransitions}: ACTIVE and PAST_DUE are the billable
 * pair that move between each other; CANCELED, ENDED and INCOMPLETE_EXPIRED are final. INCOMPLETE
 * leaves once: paid (ACTIVE), unpaid (INCOMPLETE_EXPIRED) or given up by the merchant (CANCELED).
 */
public final class SubscriptionTransitions {
  public record Transition(SubscriptionStatus from, SubscriptionStatus to) {}

  private static final Set<Transition> TABLE =
      Set.of(
          new Transition(SubscriptionStatus.INCOMPLETE, SubscriptionStatus.ACTIVE),
          new Transition(SubscriptionStatus.INCOMPLETE, SubscriptionStatus.INCOMPLETE_EXPIRED),
          new Transition(SubscriptionStatus.INCOMPLETE, SubscriptionStatus.CANCELED),
          new Transition(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE),
          new Transition(SubscriptionStatus.PAST_DUE, SubscriptionStatus.ACTIVE),
          new Transition(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELED),
          new Transition(SubscriptionStatus.PAST_DUE, SubscriptionStatus.CANCELED),
          new Transition(SubscriptionStatus.ACTIVE, SubscriptionStatus.ENDED),
          new Transition(SubscriptionStatus.PAST_DUE, SubscriptionStatus.ENDED));

  private SubscriptionTransitions() {}

  public static boolean allowed(SubscriptionStatus from, SubscriptionStatus to) {
    return TABLE.contains(new Transition(from, to));
  }
}
