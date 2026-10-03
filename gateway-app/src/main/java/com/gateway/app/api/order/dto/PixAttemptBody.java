package com.gateway.app.api.order.dto;

import com.gateway.billing.order.AttemptRequest;

public record PixAttemptBody(Integer expiresIn) implements OrderAttemptRequest {

  @Override
  public AttemptRequest toAttempt() {
    if (expiresIn != null && expiresIn <= 0) {
      throw new IllegalArgumentException("expires_in must be positive seconds");
    }

    return new AttemptRequest.PixAttempt(expiresIn);
  }
}
