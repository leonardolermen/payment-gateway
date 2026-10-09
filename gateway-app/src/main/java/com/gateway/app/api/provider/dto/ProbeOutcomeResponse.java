package com.gateway.app.api.provider.dto;

import com.gateway.merchants.credential.ProviderCredential;
import java.time.Instant;

/** The last "test connection": {@code detail} is one of the probe's fixed phrases. */
public record ProbeOutcomeResponse(boolean ok, String detail, Instant checkedAt) {
  public static ProbeOutcomeResponse from(ProviderCredential.ProbeOutcome outcome) {
    if (outcome == null) {
      return null;
    }

    return new ProbeOutcomeResponse(outcome.ok(), outcome.detail(), outcome.checkedAt());
  }
}
