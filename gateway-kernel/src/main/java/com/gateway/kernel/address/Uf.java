package com.gateway.kernel.address;

import com.gateway.kernel.errors.InvalidValue;
import java.util.Locale;
import java.util.regex.Pattern;

/** The two-letter Brazilian state code, upper-cased. */
public record Uf(String value) {
  private static final Pattern TWO_LETTERS = Pattern.compile("[A-Z]{2}");

  public static Uf of(String raw) {
    // "sp" is a valid UF typed in lowercase, not a wrong one; the bank's enum is uppercase, so it is
    // normalized, not refused.
    String normalised = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);

    if (!TWO_LETTERS.matcher(normalised).matches()) {
      throw new InvalidValue("must be a two-letter UF");
    }

    return new Uf(normalised);
  }
}
