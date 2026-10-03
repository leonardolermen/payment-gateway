package com.gateway.app.api.plan.dto;

import com.gateway.billing.plan.Plan;
import java.time.Instant;

public record PlanResponse(
    String id,
    String name,
    long amount,
    String currency,
    String interval,
    int intervalCount,
    int trialDays,
    boolean active,
    Instant createdAt) {

  public static PlanResponse from(Plan plan) {
    return new PlanResponse(
        plan.id(),
        plan.name(),
        plan.amount().cents(),
        plan.amount().currency(),
        plan.interval().name(),
        plan.intervalCount(),
        plan.trialDays(),
        plan.active(),
        plan.createdAt());
  }
}
