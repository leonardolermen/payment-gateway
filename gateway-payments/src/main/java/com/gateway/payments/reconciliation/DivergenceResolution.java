package com.gateway.payments.reconciliation;

import java.util.EnumSet;
import java.util.Set;

/**
 * The operator's verdict. Each origin has its own vocabulary: the bank disagreeing is either
 * CONFIRMED or a FALSE_POSITIVE; a merchant's dispute is RESOLVED in its favor or REJECTED. Mixing
 * them would let a dispute be "confirmed", which says nothing about who was right.
 */
public enum DivergenceResolution {
  CONFIRMED(DivergenceOrigin.SYSTEM, DivergenceStatus.RESOLVED),
  FALSE_POSITIVE(DivergenceOrigin.SYSTEM, DivergenceStatus.RESOLVED),
  RESOLVED(DivergenceOrigin.MERCHANT, DivergenceStatus.RESOLVED),
  REJECTED(DivergenceOrigin.MERCHANT, DivergenceStatus.REJECTED);

  private final DivergenceOrigin origin;
  private final DivergenceStatus status;

  DivergenceResolution(DivergenceOrigin origin, DivergenceStatus status) {
    this.origin = origin;
    this.status = status;
  }

  public static Set<DivergenceResolution> allowedFor(DivergenceOrigin origin) {
    Set<DivergenceResolution> allowed = EnumSet.noneOf(DivergenceResolution.class);
    for (DivergenceResolution resolution : values()) {
      if (resolution.origin == origin) {
        allowed.add(resolution);
      }
    }
    return allowed;
  }

  public DivergenceStatus toStatus() {
    return status;
  }
}
