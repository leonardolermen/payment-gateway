package com.gateway.app.api.admin.payment;

import com.gateway.app.api.admin.payment.dto.StuckPaymentResponse;
import com.gateway.app.api.admin.payment.dto.StuckPaymentsResponse;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.StuckPayments;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payments the sweeps should have moved, at most 100 of each kind, oldest first. */
@RestController
public class StuckPaymentsAdminController {
  private final StuckPayments stuck;
  private final Clock clock;

  public StuckPaymentsAdminController(StuckPayments stuck, Clock clock) {
    this.stuck = stuck;
    this.clock = clock;
  }

  @GetMapping("/v1/admin/payments/stuck")
  public StuckPaymentsResponse list() {
    Instant now = clock.instant();

    return new StuckPaymentsResponse(
        responses(stuck.createdTooLong(now)), responses(stuck.pendingPastExpiry(now)));
  }

  private static List<StuckPaymentResponse> responses(List<Payment> payments) {
    return payments.stream().map(StuckPaymentResponse::from).toList();
  }
}
