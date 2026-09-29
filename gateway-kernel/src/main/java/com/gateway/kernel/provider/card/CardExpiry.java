package com.gateway.kernel.provider.card;

import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The month printed on the card, in the {@code MM/YYYY} both the API and the Cielo use. Valid
 * through the last day of that month, so the current month is accepted (Review Focus 3).
 *
 * <p>{@code currentMonth} is a parameter, not {@code YearMonth.now()}: the kernel has no clock, and
 * "the current month" must be the caller's (São Paulo) month, not the JVM's.
 */
public record CardExpiry(YearMonth value) {
  private static final Pattern MONTH_SLASH_YEAR = Pattern.compile("(0[1-9]|1[0-2])/(\\d{4})");

  public static CardExpiry of(String raw, YearMonth currentMonth) {
    Matcher matcher = MONTH_SLASH_YEAR.matcher(raw == null ? "" : raw.trim());

    if (!matcher.matches()) {
      throw new InvalidCardValue("expiry", "must be MM/YYYY");
    }

    YearMonth value =
        YearMonth.of(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
    if (value.isBefore(currentMonth)) {
      throw new InvalidCardValue("expiry", "must not be in the past");
    }

    return new CardExpiry(value);
  }

  public String formatted() {
    return String.format("%02d/%04d", value.getMonthValue(), value.getYear());
  }
}
