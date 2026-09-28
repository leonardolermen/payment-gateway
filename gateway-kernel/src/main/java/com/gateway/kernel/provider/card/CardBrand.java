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

    int two = Integer.parseInt(digits.substring(0, 2));
    int four = Integer.parseInt(digits.substring(0, 4));

    if (digits.startsWith("4")) {
      return Optional.of(VISA);
    }
    if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
      return Optional.of(MASTER);
    }
    if (two == 34 || two == 37) {
      return Optional.of(AMEX);
    }
    if (four >= 3528 && four <= 3589) {
      return Optional.of(JCB);
    }
    if (two == 36 || two == 38 || (four >= 3000 && four <= 3059)) {
      return Optional.of(DINERS);
    }
    if (digits.startsWith("6011") || two == 65) {
      return Optional.of(DISCOVER);
    }
    if (two == 50) {
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
