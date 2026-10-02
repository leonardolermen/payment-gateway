package com.gateway.app.api.plan.dto;

import com.gateway.app.api.payment.dto.RequestedAmount;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanFactory;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.kernel.ids.MerchantId;
import java.time.Clock;

/** POST /v1/plans. The ranges are PlanFactory's; this only reads the amount like a payment does. */
public record PlanRequest(
    String name,
    Long amount,
    String currency,
    PlanInterval interval,
    Integer intervalCount,
    Integer trialDays) {

  public Plan toPlan(MerchantId merchantId, Clock clock) {
    return PlanFactory.fromRequest(
        merchantId,
        name,
        RequestedAmount.of(amount, currency),
        interval,
        intervalCount,
        trialDays,
        clock);
  }
}
