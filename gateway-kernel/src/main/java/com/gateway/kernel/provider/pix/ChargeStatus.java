package com.gateway.kernel.provider.pix;

/**
 * Normalized charge status. Names follow the Bacen enum, not Itaú's prose ("REMOVIDO_..." in the
 * portal text vs "REMOVIDA_..." in the OpenAPI) — see docs/providers/itau/NOTES.md.
 */
public enum ChargeStatus {
  ACTIVE,
  COMPLETED,
  REMOVED_BY_MERCHANT,
  REMOVED_BY_PSP
}
