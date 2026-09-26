package com.gateway.kernel.party;

import com.gateway.kernel.errors.InvalidValue;
import java.util.regex.Pattern;

/**
 * A CPF (11 digits) or a CNPJ (14 digits), punctuation stripped. Merchants send it typed as people
 * type it — {@code 529.982.247-25} — and the bank wants the digits; normalising here means no
 * caller has to remember which of the two it is holding.
 */
public record Document(String digits) {
  private static final Pattern CPF_OR_CNPJ = Pattern.compile("\\d{11}|\\d{14}");

  public static Document of(String raw) {
    String digits = raw == null ? "" : raw.replaceAll("\\D", "");

    if (!CPF_OR_CNPJ.matcher(digits).matches()) {
      throw new InvalidValue("must be a CPF (11 digits) or CNPJ (14 digits)");
    }

    return new Document(digits);
  }

  /** A CPF is a person, a CNPJ a company, and the bank's payload names them differently. */
  public boolean isCompany() {
    return digits.length() == 14;
  }
}
