package com.gateway.payments.payment;

import static com.gateway.payments.payment.EventSource.*;
import static com.gateway.payments.payment.PaymentStatus.*;

import java.util.EnumSet;
import java.util.Set;

/**
 * The state machine as a table (spec section 3.1). A transition is (from, to, who may trigger it):
 * the provider may complete an EXPIRED charge because the bank settles up to the last second and
 * its webhook arrives after our expiry job ran — the bank wins. Nobody else may.
 */
public final class PaymentTransitions {
  public record Transition(PaymentStatus from, PaymentStatus to, Set<EventSource> by) {}

  private static final Set<Transition> TABLE =
      Set.of(
          // SYSTEM: the stuck-CREATED sweeper adopts a charge the bank confirms exists (the createCharge
          // response was lost). Not RECONCILIATION: that source means "the bank says it was paid".
          new Transition(CREATED, PENDING, EnumSet.of(API, SYSTEM)),
          new Transition(CREATED, FAILED, EnumSet.of(API, SYSTEM)),
          new Transition(PENDING, COMPLETED, EnumSet.of(PROVIDER_WEBHOOK, RECONCILIATION)),
          new Transition(PENDING, EXPIRED, EnumSet.of(EXPIRATION_JOB)),
          new Transition(PENDING, CANCELED, EnumSet.of(API)),
          new Transition(EXPIRED, COMPLETED, EnumSet.of(PROVIDER_WEBHOOK, RECONCILIATION)));

  private PaymentTransitions() {}

  public static Set<Transition> table() {
    return TABLE;
  }

  public static boolean allowed(PaymentStatus from, PaymentStatus to, EventSource by) {
    return TABLE.stream().anyMatch(t -> t.from() == from && t.to() == to && t.by().contains(by));
  }
}
