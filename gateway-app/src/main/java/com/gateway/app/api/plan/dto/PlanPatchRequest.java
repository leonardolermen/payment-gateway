package com.gateway.app.api.plan.dto;

import com.gateway.billing.plan.PlanInterval;
import com.gateway.kernel.errors.DomainException;
import java.util.List;

/**
 * PATCH /v1/plans/{id}: only {@code name} and {@code active} change. The price and interval fields
 * are declared anyway so that sending one is 422 PLAN_IMMUTABLE naming it (spec §5), not the
 * anonymous 400 the mapper's FAIL_ON_UNKNOWN_PROPERTIES would give: a running subscription never
 * moves price, and the merchant is told to create a new plan.
 */
public record PlanPatchRequest(
    String name,
    Boolean active,
    Long amount,
    String currency,
    PlanInterval interval,
    Integer intervalCount,
    Integer trialDays) {

  /** A field in the client's spelling, with what was sent for it. */
  private record SentField(String name, Object value) {}

  public void validate() {
    immutableFields().stream()
        .filter(field -> field.value() != null)
        .findFirst()
        .ifPresent(
            field -> {
              throw new DomainException(
                  "PLAN_IMMUTABLE", field.name() + " cannot change; create a new plan");
            });

    if (name == null && active == null) {
      throw new IllegalArgumentException("at least one of name or active is required");
    }
  }

  private List<SentField> immutableFields() {
    return List.of(
        new SentField("amount", amount),
        new SentField("currency", currency),
        new SentField("interval", interval),
        new SentField("interval_count", intervalCount),
        new SentField("trial_days", trialDays));
  }
}
