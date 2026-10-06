package com.gateway.payments.reconciliation;

/**
 * Who raised a divergence. SYSTEM: reconciliation, settlement and the card/boleto paths found the
 * bank disagreeing. MERCHANT: a dispute the merchant opened on its own payment.
 */
public enum DivergenceOrigin {
  SYSTEM,
  MERCHANT
}
