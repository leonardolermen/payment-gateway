package com.gateway.payments.dispute;

/** Why a merchant disputes one of its payments; stored by name in the divergence's reason. */
public enum DisputeReason {
  AMOUNT_MISMATCH,
  NOT_SETTLED,
  DUPLICATE,
  OTHER
}
