package com.gateway.payments.reconciliation;

import java.util.List;

public enum DivergenceStatus {
  OPEN,
  UNDER_REVIEW,
  RESOLVED,
  REJECTED;

  /**
   * Statuses in which a human has not finished with the case yet. Must match the status list of the
   * partial unique indexes of V207: a duplicate guard that covers fewer statuses lets a second row
   * in beside a case already being reviewed.
   */
  public static final List<String> UNSETTLED_NAMES = List.of(OPEN.name(), UNDER_REVIEW.name());

  public boolean isFinal() {
    return this == RESOLVED || this == REJECTED;
  }
}
