package com.gateway.billing.plan;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import java.time.Instant;

/**
 * Price and interval have no wither: changing them is a new plan (spec 4.4), so a running
 * subscription can never move by accident. Only the label and the availability change.
 */
public record Plan(
    String id,
    MerchantId merchantId,
    String name,
    Money amount,
    PlanInterval interval,
    int intervalCount,
    int trialDays,
    boolean active,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public Plan rename(String newName, Instant at) {
    return changed(newName, active, at);
  }

  public Plan activate(Instant at) {
    return changed(name, true, at);
  }

  public Plan deactivate(Instant at) {
    return changed(name, false, at);
  }

  private Plan changed(String newName, boolean newActive, Instant at) {
    return new Plan(
        id,
        merchantId,
        newName,
        amount,
        interval,
        intervalCount,
        trialDays,
        newActive,
        version + 1,
        createdAt,
        at);
  }
}
