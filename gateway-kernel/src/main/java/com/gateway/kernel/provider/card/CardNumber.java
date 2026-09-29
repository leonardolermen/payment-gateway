package com.gateway.kernel.provider.card;

import java.util.regex.Pattern;

/**
 * A primary account number, digits only. Spaces and dashes are removed because that is how a payer
 * types it (Review Focus 1); anything else is refused. Not a record on purpose: a record's
 * generated toString would print the digits, and so would every exception built from one.
 *
 * <p>{@link #reveal()} is the one way out, named like {@code Secret.reveal()} so the call site
 * reads as the decision it is. The only production callers are the two Cielo request factories.
 */
public final class CardNumber {
  private static final Pattern GROUPING = Pattern.compile("[\\s-]");
  private static final Pattern DIGITS_13_TO_19 = Pattern.compile("\\d{13,19}");

  private final String digits;

  private CardNumber(String digits) {
    this.digits = digits;
  }

  public static CardNumber of(String raw) {
    String digits = raw == null ? "" : GROUPING.matcher(raw).replaceAll("");

    if (!DIGITS_13_TO_19.matcher(digits).matches()) {
      throw new InvalidCardValue("number", "must be 13 to 19 digits");
    }
    if (!passesLuhn(digits)) {
      throw new InvalidCardValue("number", "must pass the Luhn check");
    }

    return new CardNumber(digits);
  }

  public String reveal() {
    return digits;
  }

  public String last4() {
    return digits.substring(digits.length() - 4);
  }

  public String first6() {
    return digits.substring(0, 6);
  }

  /** The PCI display form: BIN and last four, the middle starred. */
  public String masked() {
    return first6() + "*".repeat(digits.length() - 10) + last4();
  }

  @Override
  public String toString() {
    return "****" + last4();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CardNumber number && number.digits.equals(digits);
  }

  @Override
  public int hashCode() {
    return digits.hashCode();
  }

  /**
   * Mod 10. The sandbox and production both apply it before anything else
   * (reference/credito-sandbox: "regra do mod10 (Algoritimo de Luhn), que é empregada nos ambientes
   * Sandbox e de Produção"), so refusing here saves a round trip that could only end in a 400.
   */
  private static boolean passesLuhn(String digits) {
    int sum = 0;
    boolean doubleIt = false;

    for (int i = digits.length() - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubleIt = !doubleIt;
    }

    return sum % 10 == 0;
  }
}
