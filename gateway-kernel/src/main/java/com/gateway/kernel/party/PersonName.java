package com.gateway.kernel.party;

import com.gateway.kernel.errors.InvalidValue;
import java.util.regex.Pattern;

/**
 * Someone's name. The only rule is that it contains a letter: a name of digits or punctuation is a
 * missing name, and anything stricter would refuse names that exist. Kept exactly as typed —
 * truncating to the bank's field width is the provider's business, not the name's.
 */
public record PersonName(String value) {
  private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");

  public static PersonName of(String raw) {
    if (raw == null || !HAS_LETTER.matcher(raw).matches()) {
      throw new InvalidValue("is required");
    }

    return new PersonName(raw);
  }
}
