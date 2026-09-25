package com.gateway.providers.itau.pix;

import com.gateway.kernel.money.Money;
import java.util.regex.Pattern;

/**
 * The one place that turns cents into the Bacen string ({@code \d{1,10}\.\d{2}}) and back.
 * Public: Task 4's dto package imports it to build the {@code cob} request body.
 */
public final class PixAmounts {
  private static final Pattern BACEN = Pattern.compile("\\d{1,10}\\.\\d{2}");
  private static final long MAX_CENTS = 999_999_999_999L; // 9999999999.99

  private PixAmounts() {}

  public static String toItau(Money m) {
    if (!"BRL".equals(m.currency())) throw new IllegalArgumentException("Pix is BRL only: " + m.currency());
    if (m.cents() <= 0 || m.cents() > MAX_CENTS) throw new IllegalArgumentException("amount outside the Bacen range: " + m.cents());
    return m.cents() / 100 + "." + String.format("%02d", m.cents() % 100);
  }

  public static Money fromItau(String s) {
    if (s == null || !BACEN.matcher(s).matches()) throw new IllegalArgumentException("not a Bacen amount: " + s);
    String[] p = s.split("\\.");
    return Money.brl(Long.parseLong(p[0]) * 100 + Long.parseLong(p[1]));
  }
}
