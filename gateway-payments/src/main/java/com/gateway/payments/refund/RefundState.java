package com.gateway.payments.refund;

/**
 * {@code UNKNOWN}: polling ran out of budget with the bank still saying EM_PROCESSAMENTO. Terminal
 * for polling, but NOT for the reserve: the money may still leave at the bank, so the amount stays
 * reserved against the payment, and a later word from the bank may still move it to COMPLETED or
 * FAILED. Only FAILED frees the amount.
 */
public enum RefundState {
  REQUESTED,
  PROCESSING,
  COMPLETED,
  FAILED,
  UNKNOWN
}
