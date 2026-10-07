package com.gateway.billing.installment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The installment options of an amount under a merchant's settings (spec 2026-10-07 §3). Pure: no
 * clock, no database, so the table test is the whole contract.
 *
 * <ul>
 *   <li>{@code n <= interestFreeUpTo}, or a rate of 0: total = amount, installment = amount / n
 *       truncated to the cent. The acquirer divides the total itself; the cents left over go into
 *       its installments, never above what the payer agreed to.
 *   <li>{@code n > interestFreeUpTo}: Price table, {@code installment = amount × i / (1 − (1 +
 *       i)^−n)}, rounded <b>up</b> to the cent, and {@code total = installment × n}. The total is
 *       an exact multiple of n, so the acquirer's division gives the very installment shown.
 *   <li>An option whose installment is under R$ 5,00 is not offered (the Cielo's minimum for
 *       merchant-financed installments, same rule as {@code InstallmentPlan}); 1x always is.
 * </ul>
 */
public final class InstallmentPricing {
  /** R$ 5,00: the Cielo's minimum per installment for ByMerchant (plan D5). */
  public static final long MINIMUM_INSTALLMENT_CENTS = 500;

  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

  /**
   * DECIMAL64 keeps 16 significant digits, so an installment that is exactly a whole cent can come
   * out as {@code 1234.000000000001}; ceiling that would charge a cent nobody owes. Six places is
   * far below a cent and far above the arithmetic's noise.
   */
  private static final int NOISE_SCALE = 6;

  private InstallmentPricing() {}

  /** Every count from 1 to {@code maxInstallments} that clears the minimum, ascending. */
  public static List<InstallmentOption> options(long amount, InstallmentSettings settings) {
    List<InstallmentOption> options = new ArrayList<>();
    for (int count = 1; count <= settings.maxInstallments(); count++) {
      option(amount, settings, count).ifPresent(options::add);
    }

    return options;
  }

  /** Empty when {@code count} is not offered: out of 1..max or under the minimum. */
  public static Optional<InstallmentOption> option(
      long amount, InstallmentSettings settings, int count) {
    if (count < 1 || count > settings.maxInstallments()) {
      return Optional.empty();
    }

    InstallmentOption option = price(amount, settings, count);
    if (count > 1 && option.installmentAmount() < MINIMUM_INSTALLMENT_CENTS) {
      return Optional.empty();
    }

    return Optional.of(option);
  }

  private static InstallmentOption price(long amount, InstallmentSettings settings, int count) {
    if (count <= settings.interestFreeUpTo() || settings.monthlyRateBps() == 0) {
      return new InstallmentOption(count, amount / count, amount, true);
    }

    BigDecimal rate = BigDecimal.valueOf(settings.monthlyRateBps()).divide(BPS, MATH);
    BigDecimal discount = BigDecimal.ONE.add(rate, MATH).pow(-count, MATH);
    BigDecimal installment =
        BigDecimal.valueOf(amount)
            .multiply(rate, MATH)
            .divide(BigDecimal.ONE.subtract(discount, MATH), MATH)
            .setScale(NOISE_SCALE, RoundingMode.HALF_EVEN)
            .setScale(0, RoundingMode.CEILING);

    long cents = installment.longValueExact();
    return new InstallmentOption(count, cents, cents * count, false);
  }
}
