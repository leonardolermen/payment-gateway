package com.gateway.kernel.provider.card;

import java.util.regex.Pattern;

/**
 * The name embossed on the card: letters and spaces, at most 25 (the Cielo's {@code Holder} size,
 * reference/criar-pagamento-credito). Accented letters are kept — the transliteration the Cielo
 * needs is the provider's text rule, not the payer's name.
 */
public record CardHolder(String value) {
  private static final Pattern LETTERS_AND_SPACES = Pattern.compile("[\\p{L} ]+");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final int MAX = 25;

  public static CardHolder of(String raw) {
    String trimmed = raw == null ? "" : SPACES.matcher(raw.trim()).replaceAll(" ");

    if (trimmed.isEmpty() || !LETTERS_AND_SPACES.matcher(trimmed).matches()) {
      throw new InvalidCardValue("holder", "must contain only letters and spaces");
    }
    if (trimmed.length() > MAX) {
      throw new InvalidCardValue("holder", "must be at most 25 characters");
    }

    return new CardHolder(trimmed);
  }
}
