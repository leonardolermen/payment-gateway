package com.gateway.app.api.dispute.dto;

import com.gateway.payments.dispute.DisputeReason;

/** An unknown {@code reason} never gets here: Jackson refuses the enum and the body is a 400. */
public record OpenDisputeRequest(DisputeReason reason, String note) {
  public OpenDisputeRequest {
    if (reason == null) {
      throw new IllegalArgumentException("reason is required");
    }
  }
}
