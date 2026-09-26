package com.gateway.kernel.money;

/**
 * Money as integer cents. Never {@code double}: 0.10 + 0.20 is not 0.30 in floating point, and a
 * gateway that is one cent off loses the whole reconciliation.
 */
public record Money(long cents, String currency) {
  public static final Money ZERO_BRL = new Money(0, "BRL");

  public Money {
    if (cents < 0) {
      throw new IllegalArgumentException("negative amount: " + cents);
    }
    if (currency == null || currency.length() != 3) {
      throw new IllegalArgumentException("invalid currency: " + currency);
    }
  }

  public static Money brl(long cents) {
    return new Money(cents, "BRL");
  }

  public Money plus(Money other) {
    return new Money(cents + sameCurrency(other).cents, currency);
  }

  public Money minus(Money other) {
    long r = cents - sameCurrency(other).cents;
    if (r < 0) {
      throw new IllegalArgumentException("negative result");
    }
    return new Money(r, currency);
  }

  public boolean greaterThan(Money other) {
    return cents > sameCurrency(other).cents;
  }

  public boolean isZero() {
    return cents == 0;
  }

  private Money sameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currency + " vs " + other.currency);
    }
    return other;
  }
}
