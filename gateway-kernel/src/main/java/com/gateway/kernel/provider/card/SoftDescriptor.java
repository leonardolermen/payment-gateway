package com.gateway.kernel.provider.card;

import com.gateway.kernel.errors.InvalidValue;
import java.util.regex.Pattern;

/**
 * The text after the store name on the payer's statement: up to 13 characters, no special ones
 * (reference/criar-pagamento-credito, {@code SoftDescriptor}: "Não permite caracteres especiais.
 * Tamanho: 13"). Refused rather than truncated: the merchant chose the words and should see them
 * printed as chosen.
 */
public record SoftDescriptor(String value) {
  private static final Pattern ALPHANUMERIC_UP_TO_13 = Pattern.compile("[A-Za-z0-9]{1,13}");

  /** Null stays null: the Cielo then prints the store name alone. */
  public static SoftDescriptor ofNullable(String raw) {
    if (raw == null) {
      return null;
    }
    if (!ALPHANUMERIC_UP_TO_13.matcher(raw).matches()) {
      throw new InvalidValue("must be 1 to 13 letters or digits");
    }

    return new SoftDescriptor(raw);
  }
}
