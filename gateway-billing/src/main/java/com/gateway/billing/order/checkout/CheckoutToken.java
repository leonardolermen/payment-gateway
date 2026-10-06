package com.gateway.billing.order.checkout;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The secret in the payer's link. 32 random bytes (256 bits): a brute force against the hash is out
 * of reach even with a fast hash, which is why the row keeps a peppered SHA-256 and not bcrypt
 * (same reasoning as ApiKey). The prefix lets a log scrubber and a human recognise one; it carries
 * no information.
 */
public record CheckoutToken(String value) {
  private static final Pattern SHAPE = Pattern.compile("^chk_[A-Za-z0-9_-]{43}$");
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  public static CheckoutToken generate(SecureRandom random) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);

    return new CheckoutToken("chk_" + URL.encodeToString(bytes));
  }

  /** Empty for anything that is not shaped like a token: those never reach the database. */
  public static Optional<CheckoutToken> parse(String raw) {
    if (raw == null || !SHAPE.matcher(raw).matches()) {
      return Optional.empty();
    }

    return Optional.of(new CheckoutToken(raw));
  }

  @Override
  public String toString() {
    return "chk_****";
  }
}
