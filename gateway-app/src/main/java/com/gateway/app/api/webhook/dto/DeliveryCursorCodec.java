package com.gateway.app.api.webhook.dto;

import com.barrier.webhookdelivery.domain.DeliveryCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * The {@code after} cursor of the deliveries list. Opaque to the merchant on purpose: the
 * (created_at, id) pair is the lib's keyset, and exposing it as two query params would make its
 * shape contract. A cursor that does not decode is the client's mistake, so it is an
 * IllegalArgumentException (400), never a 500.
 */
public final class DeliveryCursorCodec {
  private static final String SEPARATOR = "|";
  private static final String INVALID = "after is not a valid cursor";

  private DeliveryCursorCodec() {}

  public static String encode(DeliveryCursor cursor) {
    String raw = cursor.createdAt().toString() + SEPARATOR + cursor.id();

    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static DeliveryCursor decode(String encoded) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      int separator = raw.indexOf(SEPARATOR);
      if (separator < 0) {
        throw new IllegalArgumentException(INVALID);
      }

      return new DeliveryCursor(
          Instant.parse(raw.substring(0, separator)),
          UUID.fromString(raw.substring(separator + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new IllegalArgumentException(INVALID, e);
    }
  }
}
