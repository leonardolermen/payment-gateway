package com.gateway.merchants.user;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Trimmed on the way in; compared lower-cased. Shape only: deliverability is the mail's problem.
 */
public record EmailAddress(String value) {
  private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final int MAX = 254;

  public EmailAddress {
    if (value == null) {
      throw new IllegalArgumentException("email is required");
    }
    value = value.trim();
    if (value.length() > MAX || !SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("email is not a valid address");
    }
  }

  public String normalized() {
    return value.toLowerCase(Locale.ROOT);
  }
}
