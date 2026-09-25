package com.gateway.kernel.provider.pix;

/**
 * Refund is asynchronous at Itaú (201 PROCESSING, final status by webhook or polling) — see
 * docs/providers/itau/NOTES.md. The gateway normalizes to these three states for every provider.
 */
public enum RefundStatus {
  PROCESSING,
  COMPLETED,
  FAILED
}
