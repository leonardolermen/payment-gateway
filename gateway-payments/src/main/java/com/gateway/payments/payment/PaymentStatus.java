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

  /** The statuses of uq_payments_order_active (V206): an attempt that still holds its order. */
  private static final Set<PaymentStatus> ACTIVE = EnumSet.of(CREATED, PENDING, AUTHORIZED);

  public boolean terminal() {
    return TERMINAL.contains(this);
  }

  public boolean isActive() {
    return ACTIVE.contains(this);
  }
}
