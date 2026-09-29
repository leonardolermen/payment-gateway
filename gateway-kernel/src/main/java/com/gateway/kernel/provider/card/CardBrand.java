package com.gateway.kernel.provider.card;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The brands the Cielo accepts, exactly its {@code Brand} list (reference/criar-pagamento-credito:
 * "Visa / Master / Amex / Elo / Aura / JCB / Diners / Discover"). Hipercard is not in it (plan D4).
 *
 * <p>The BIN table is deliberately small: it only has to catch a merchant sending MASTER for a Visa
 * number, and to spare the merchant the {@code brand} field for the common cards. Unknown BIN means
 * "ask the merchant", never a guess. Elo is checked first because its ranges sit inside Visa's 4
 * and Discover's 65.
 */
public enum CardBrand {
  VISA,
  MASTER,
  AMEX,
  ELO,
  AURA,
  JCB,
  DINERS,
  DISCOVER;

  private static final List<String> ELO_PREFIXES =
      List.of(
          "401178", "401179", "431274", "438935", "451416", "457393", "457631", "457632", "504175",
          "506699", "5067", "509", "627780", "636297", "636368", "650031", "650032", "650033",
          "65004", "65005", "6504", "6505", "6507", "6509", "6516", "6550");

  public static Optional<CardBrand> fromBin(String digits) {
    if (ELO_PREFIXES.stream().anyMatch(digits::startsWith)) {
      return Optional.of(ELO);
    }

    int firstTwoDigits = Integer.parseInt(digits.substring(0, 2));
    int firstFourDigits = Integer.parseInt(digits.substring(0, 4));

    if (digits.startsWith("4")) {
      return Optional.of(VISA);
    }
    if ((firstTwoDigits >= 51 && firstTwoDigits <= 55)
        || (firstFourDigits >= 2221 && firstFourDigits <= 2720)) {
      return Optional.of(MASTER);
    }
    if (firstTwoDigits == 34 || firstTwoDigits == 37) {
      return Optional.of(AMEX);
    }
    if (firstFourDigits >= 3528 && firstFourDigits <= 3589) {
      return Optional.of(JCB);
    }
    if (firstTwoDigits == 36
        || firstTwoDigits == 38
        || (firstFourDigits >= 3000 && firstFourDigits <= 3059)) {
      return Optional.of(DINERS);
    }
    if (digits.startsWith("6011") || firstTwoDigits == 65) {
      return Optional.of(DISCOVER);
    }
    if (firstTwoDigits == 50) {
      return Optional.of(AURA);
    }

    return Optional.empty();
  }

  public static CardBrand of(String raw) {
    String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);

    return Arrays.stream(values())
        .filter(brand -> brand.name().equals(upper))
        .findFirst()
        .orElseThrow(
            () ->
                new InvalidCardValue(
                    "brand",
                    "must be one of "
                        + Arrays.stream(values())
                            .map(Enum::name)
                            .collect(Collectors.joining(", "))));
  }

  /** American Express prints four digits on the front; every other brand three on the back. */
  public int cvvLength() {
    return this == AMEX ? 4 : 3;
  }
}
