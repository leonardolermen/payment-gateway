package com.gateway.providers.itau.boleto;

import com.gateway.kernel.money.Money;
import java.util.regex.Pattern;

/** The one place that turns cents into the boleto string ({@code ^\d+\.\d{2}$}, 15 integer digits) and back. */
public final class BoletoAmounts {
  private static final Pattern BANK = Pattern.compile("\\d{1,15}\\.\\d{2}");
  private static final long MAX_CENTS = 99_999_999_999_999_999L;

  private BoletoAmounts() {}

  public static String toItau(Money m) {
    if (!"BRL".equals(m.currency())) throw new IllegalArgumentException("boleto is BRL only: " + m.currency());
    if (m.cents() <= 0 || m.cents() > MAX_CENTS) throw new IllegalArgumentException("amount outside the boleto range: " + m.cents());

    return m.cents() / 100 + "." + String.format("%02d", m.cents() % 100);
  }

  public static Money fromItau(String s) {
    if (s == null || !BANK.matcher(s).matches()) throw new IllegalArgumentException("not a boleto amount: " + s);
    String[] p = s.split("\\.");

    return Money.brl(Long.parseLong(p[0]) * 100 + Long.parseLong(p[1]));
  }
}
