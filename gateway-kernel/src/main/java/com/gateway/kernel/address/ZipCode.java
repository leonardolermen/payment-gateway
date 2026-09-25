package com.gateway.kernel.address;

import com.gateway.kernel.errors.InvalidValue;
import java.util.regex.Pattern;

/** A CEP, eight digits, punctuation stripped: {@code 01310-100} and {@code 01310100} are the same one. */
public record ZipCode(String digits) {
  private static final Pattern EIGHT_DIGITS = Pattern.compile("\\d{8}");

  public static ZipCode of(String raw) {
    String digits = raw == null ? "" : raw.replaceAll("\\D", "");

    if (!EIGHT_DIGITS.matcher(digits).matches()) {
      throw new InvalidValue("must be 8 digits");
    }

    return new ZipCode(digits);
  }
}
