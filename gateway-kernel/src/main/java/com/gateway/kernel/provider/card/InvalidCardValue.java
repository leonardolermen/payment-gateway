package com.gateway.kernel.provider.card;

import com.gateway.kernel.errors.InvalidValue;

/**
 * A card field with the wrong shape. Unlike its parent it knows which field — "number", "expiry",
 * "cvv" — because a card is validated as a whole (the CVV length depends on the brand, the brand on
 * the number) and the caller could not tell which part failed. The API prefix ({@code card.}) is
 * still the caller's: this type does not know it is called {@code card.number} on the wire.
 *
 * <p>The message never carries the value: a rejected card number is still a card number.
 */
public class InvalidCardValue extends InvalidValue {
  private final String field;

  public InvalidCardValue(String field, String reason) {
    super(reason);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
