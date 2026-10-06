package com.gateway.payments.reconciliation;

import java.util.Set;

/**
 * The state machine as a table. UNDER_REVIEW never goes back to OPEN: the SYSTEM unique index is
 * keyed on OPEN, so a reopened row could collide with one the bank raised meanwhile.
 */
public final class DivergenceTransitions {
  public record Transition(DivergenceStatus from, DivergenceStatus to) {}

  private static final Set<Transition> TABLE =
      Set.of(
          new Transition(DivergenceStatus.OPEN, DivergenceStatus.UNDER_REVIEW),
          new Transition(DivergenceStatus.OPEN, DivergenceStatus.RESOLVED),
          new Transition(DivergenceStatus.OPEN, DivergenceStatus.REJECTED),
          new Transition(DivergenceStatus.UNDER_REVIEW, DivergenceStatus.RESOLVED),
          new Transition(DivergenceStatus.UNDER_REVIEW, DivergenceStatus.REJECTED));

  private DivergenceTransitions() {}

  public static boolean allowed(DivergenceStatus from, DivergenceStatus to) {
    return TABLE.contains(new Transition(from, to));
  }
}
