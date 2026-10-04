package com.gateway.billing.order;

import java.util.Set;

/**
 * The state machine as a table (spec §4.2): OPEN is the only state that moves; the rest is final.
 */
public final class OrderTransitions {
  public record Transition(OrderStatus from, OrderStatus to) {}

  private static final Set<Transition> TABLE =
      Set.of(
          new Transition(OrderStatus.OPEN, OrderStatus.PAID),
          new Transition(OrderStatus.OPEN, OrderStatus.CANCELED),
          new Transition(OrderStatus.OPEN, OrderStatus.EXPIRED));

  private OrderTransitions() {}

  public static boolean allowed(OrderStatus from, OrderStatus to) {
    return TABLE.contains(new Transition(from, to));
  }
}
