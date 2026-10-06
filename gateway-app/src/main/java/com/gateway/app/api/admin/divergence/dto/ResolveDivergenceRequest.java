package com.gateway.app.api.admin.divergence.dto;

import com.gateway.payments.reconciliation.DivergenceResolution;

public record ResolveDivergenceRequest(DivergenceResolution resolution, String note) {
  /** Without it a missing resolution would reach the domain as "null does not apply" (422). */
  public ResolveDivergenceRequest {
    if (resolution == null) {
      throw new IllegalArgumentException("resolution is required");
    }
  }
}
