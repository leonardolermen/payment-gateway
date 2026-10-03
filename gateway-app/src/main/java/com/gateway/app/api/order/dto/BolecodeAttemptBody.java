package com.gateway.app.api.order.dto;

import com.gateway.billing.order.AttemptRequest;
import java.time.LocalDate;

/** The payer and the address come from the order; a missing address is the domain's 422. */
public record BolecodeAttemptBody(LocalDate dueDate, Integer paymentLimitDays)
    implements OrderAttemptRequest {

  @Override
  public AttemptRequest toAttempt() {
    if (paymentLimitDays != null && paymentLimitDays < 0) {
      throw new IllegalArgumentException("payment_limit_days must not be negative");
    }

    return new AttemptRequest.BolecodeAttempt(dueDate, paymentLimitDays);
  }
}
