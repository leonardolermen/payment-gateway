package com.gateway.app.api.card.dto;

import com.gateway.payments.card.SavedCard;
import java.time.Instant;

/** GET /v1/cards/{id} (spec §7): brand, last four, MM/YYYY and the holder. Never the token. */
public record CardResponse(
    String id, String brand, String last4, String expiry, String holder, Instant createdAt) {

  public static CardResponse from(SavedCard card) {
    return new CardResponse(
        card.id(),
        card.brand().name(),
        card.last4(),
        String.format("%02d/%04d", card.expiry().getMonthValue(), card.expiry().getYear()),
        card.holder(),
        card.createdAt());
  }
}
