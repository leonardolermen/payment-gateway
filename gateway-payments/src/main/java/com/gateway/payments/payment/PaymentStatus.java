package com.gateway.payments.payment;

import java.util.EnumSet;
import java.util.Set;

/** The lifecycle of a payment. Terminal statuses are where the charge stops mattering to us. */
public enum PaymentStatus {
  CREATED,
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
