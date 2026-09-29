package com.gateway.payments.payment;

import java.util.EnumSet;
import java.util.Set;

/** The lifecycle of a payment. Terminal statuses are where the charge stops mattering to us. */
public enum PaymentStatus {
  CREATED,
  /** A card authorization waiting for its capture (spec 2026-09-28 §4). Holds the payer's limit. */
  AUTHORIZED,
  PENDING,
  COMPLETED,
  EXPIRED,
  CANCELED,
  FAILED;

  private static final Set<PaymentStatus> TERMINAL = EnumSet.of(COMPLETED, CANCELED, FAILED);

  public boolean terminal() {
    return TERMINAL.contains(this);
  }
}
