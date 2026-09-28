package com.gateway.providers.itau.boleto;

import com.gateway.kernel.money.Money;
import java.util.regex.Pattern;

/**
 * The one place that turns cents into the boleto string ({@code ^\d+\.\d{2}$}, 15 integer digits)
 * and back.
 */
public final class BoletoAmounts {
  private static final Pattern BANK = Pattern.compile("\\d{1,15}\\.\\d{2}");
  private static final long MAX_CENTS = 99_999_999_999_999_999L;

  private BoletoAmounts() {}

  public static String toItau(Money money) {
    if (!"BRL".equals(money.currency())) {
      throw new IllegalArgumentException("boleto is BRL only: " + money.currency());
    }
    if (money.cents() <= 0 || money.cents() > MAX_CENTS) {
      throw new IllegalArgumentException("amount outside the boleto range: " + money.cents());
    }

    return money.cents() / 100 + "." + String.format("%02d", money.cents() % 100);
  }

  public static Money fromItau(String raw) {
    if (raw == null || !BANK.matcher(raw).matches()) {
      throw new IllegalArgumentException("not a boleto amount: " + raw);
    }
    String[] parts = raw.split("\\.");

    return Money.brl(Long.parseLong(parts[0]) * 100 + Long.parseLong(parts[1]));
  }
}
