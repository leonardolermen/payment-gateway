package com.gateway.payments.reconciliation;

public enum DivergenceStatus {
  OPEN,
  UNDER_REVIEW,
  RESOLVED,
  REJECTED;

  public boolean isFinal() {
    return this == RESOLVED || this == REJECTED;
  }
}
