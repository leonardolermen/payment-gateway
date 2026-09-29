package com.gateway.kernel.provider.card;

import java.util.EnumSet;
import java.util.Set;

/**
 * The acquirer's view of a sale, normalized. PAID is the Cielo's PaymentConfirmed (captured).
 * Mapping from the Cielo's integers is the provider's (CieloStatuses, plan D1).
 */
public enum CardStatus {
  NOT_FINISHED,
  AUTHORIZED,
  PAID,
  DENIED,
  VOIDED,
  PENDING,
  ABORTED,
  PROCESSING,
  REFUNDED;

  private static final Set<CardStatus> IN_DOUBT = EnumSet.of(NOT_FINISHED, PENDING, PROCESSING);

  /** The acquirer has not decided: ask again, never adopt. */
  public boolean inDoubt() {
    return IN_DOUBT.contains(this);
  }
}
