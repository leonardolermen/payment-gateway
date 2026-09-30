package com.gateway.kernel.provider.card;

import com.gateway.kernel.security.Secret;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A card as the payer gave it. The full number and the CVV exist in memory inside this record,
 * between the request being read and the Cielo being called, and nowhere else (spec §7).
 *
 * <p>{@code toString} is brand and last four only — the generated one would print every component.
 * Jackson must not serialize it either; the kernel has no Jackson, so the app registers an ignore
 * mixin for this type (plan C1).
 *
 * <p>The canonical constructor carries the rules that need more than one field: the brand must
 * match the BIN when the BIN is known, and the CVV length depends on the brand.
 */
public record CardData(
    CardNumber number, CardHolder holder, CardExpiry expiry, Secret securityCode, CardBrand brand)
    implements CardSource {
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  public CardData {
    Optional<CardBrand> fromBin = CardBrand.fromBin(number.reveal());
    if (fromBin.isPresent() && brand != fromBin.get()) {
      throw new InvalidCardValue("brand", "does not match the card number (" + fromBin.get() + ")");
    }

    String cvv = securityCode.reveal();
    if (!DIGITS.matcher(cvv).matches() || cvv.length() != brand.cvvLength()) {
      throw new InvalidCardValue("cvv", "must be " + brand.cvvLength() + " digits");
    }
  }

  /**
   * From the strings the merchant sent. {@code brand} may be null when the BIN identifies it; the
   * order of the checks is the order a payer would fix them in.
   */
  public static CardData of(
      String number,
      String holder,
      String expiry,
      String cvv,
      String brand,
      YearMonth currentMonth) {
    CardNumber cardNumber = CardNumber.of(number);
    CardHolder cardHolder = CardHolder.of(holder);
    CardExpiry cardExpiry = CardExpiry.of(expiry, currentMonth);

    if (cvv == null || cvv.isBlank()) {
      throw new InvalidCardValue("cvv", "is required");
    }

    CardBrand cardBrand = brandOf(cardNumber, brand);

    return new CardData(cardNumber, cardHolder, cardExpiry, Secret.of(cvv.trim()), cardBrand);
  }

  private static CardBrand brandOf(CardNumber number, String brand) {
    if (brand != null && !brand.isBlank()) {
      return CardBrand.of(brand);
    }

    return CardBrand.fromBin(number.reveal())
        .orElseThrow(
            () ->
                new InvalidCardValue(
                    "brand", "is required when the card number does not identify it"));
  }

  public String last4() {
    return number.last4();
  }

  @Override
  public String toString() {
    return brand + " " + number;
  }
}
