package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.InvalidCardValue;
import com.gateway.kernel.security.Secret;
import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * The raw card fields → {@link CardData}, with the 422 the merchant reads: CARD_INVALID naming the
 * field as the API spells it ({@code card.number must pass the Luhn check}). Like PayerFactory, it
 * owns only the field path and the code; the rules are the kernel's value objects.
 */
public final class CardDataFactory {
  private static final String CODE = "CARD_INVALID";
  private static final Pattern THREE_OR_FOUR_DIGITS = Pattern.compile("\\d{3,4}");

  private CardDataFactory() {}

  public static CardData from(
      String number,
      String holder,
      String expiry,
      String cvv,
      String brand,
      YearMonth currentMonth) {
    try {
      return CardData.of(number, holder, expiry, cvv, brand, currentMonth);
    } catch (InvalidCardValue e) {
      throw new DomainException(CODE, "card." + e.field() + " " + e.reason());
    }
  }

  /** With a card_id the brand is the stored card's, so only the shape is checked here. */
  public static Secret securityCodeForSavedCard(String cvv) {
    if (cvv == null || cvv.isBlank()) {
      throw new DomainException(CODE, "cvv is required with card_id");
    }
    if (!THREE_OR_FOUR_DIGITS.matcher(cvv.trim()).matches()) {
      throw new DomainException(CODE, "cvv must be 3 or 4 digits");
    }

    return Secret.of(cvv.trim());
  }
}
