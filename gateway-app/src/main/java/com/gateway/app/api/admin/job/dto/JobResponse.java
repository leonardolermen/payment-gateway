package com.gateway.app.api.admin.job.dto;

import com.gateway.payments.jobs.Job;
import java.time.Instant;

public record JobResponse(
    String id,
    String type,
    String refId,
    String status,
    int attempts,
    String lastError,
    Instant nextRunAt,
    Instant claimedAt,
    Instant createdAt) {
  public static JobResponse from(Job job) {
    return new JobResponse(
        job.id(),
        job.type().name(),
        job.refId(),
        job.status(),
        job.attempts(),
        job.lastError(),
        job.nextRunAt(),
        job.claimedAt(),
        job.createdAt());
  }
}
