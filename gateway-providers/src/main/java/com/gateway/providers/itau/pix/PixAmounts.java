package com.gateway.providers.itau.pix;

import com.gateway.kernel.money.Money;
import java.util.regex.Pattern;

/**
 * The one place that turns cents into the Bacen string ({@code \d{1,10}\.\d{2}}) and back. Public:
 * Task 4's dto package imports it to build the {@code cob} request body.
 */
public final class PixAmounts {
  private static final Pattern BACEN = Pattern.compile("\\d{1,10}\\.\\d{2}");
  private static final long MAX_CENTS = 999_999_999_999L; // 9999999999.99

  private PixAmounts() {}

  public static String toItau(Money money) {
    if (!"BRL".equals(money.currency())) {
      throw new IllegalArgumentException("Pix is BRL only: " + money.currency());
    }
    if (money.cents() <= 0 || money.cents() > MAX_CENTS) {
      throw new IllegalArgumentException("amount outside the Bacen range: " + money.cents());
    }
    return money.cents() / 100 + "." + String.format("%02d", money.cents() % 100);
  }

  public static Money fromItau(String raw) {
    if (raw == null || !BACEN.matcher(raw).matches()) {
      throw new IllegalArgumentException("not a Bacen amount: " + raw);
    }
    String[] parts = raw.split("\\.");
    return Money.brl(Long.parseLong(parts[0]) * 100 + Long.parseLong(parts[1]));
  }
}
