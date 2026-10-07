package com.gateway.billing.installment;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec 2026-10-07 §3 against numbers computed by hand, not by the code under test.
 *
 * <p>How the table was computed: R$ 100,00 (10000 cents) at 2,99% a month, i = 0.0299. With v =
 * 1.0299^−n, the Price installment is {@code 10000 × 0.0299 / (1 − v) = 299 / (1 − v)} cents,
 * rounded up to the next cent, and the total is that installment × n.
 *
 * <pre>
 *   n   1.0299^n     v = 1.0299^−n    299 / (1 − v)    up    total
 *   3   1.0924088    0.9154082572     3534.6240        3535  10605
 *   4   1.1250718    0.8888321752     2689.6272        2690  10760
 *   6   1.1933569    0.8379722774     1845.3632        1846  11076
 *  10   1.3426122    0.7448167221     1171.7069        1172  11720
 *  12   1.4241007    0.7021975377     1004.0212        1005  12060
 * </pre>
 *
 * Cross-checked with exact rational arithmetic (Python {@code fractions.Fraction}): the same
 * ceiling in every row. The interest-free division truncates: 10000 / 3 = 3333 (the acquirer
 * spreads the one cent left).
 */
class InstallmentPricingTest {
  static final MerchantId MERCHANT = MerchantId.next();

  static InstallmentSettings settings(int max, int freeUpTo, int bps) {
    return new InstallmentSettings(MERCHANT, ProviderEnvironment.TEST, max, freeUpTo, bps, null);
  }

  static InstallmentOption option(long amount, InstallmentSettings settings, int count) {
    return InstallmentPricing.option(amount, settings, count).orElseThrow();
  }

  @Test
  void thePriceTableAt299BasisPoints() {
    InstallmentSettings settings = settings(12, 1, 299);

    assertThat(option(10000, settings, 3)).isEqualTo(new InstallmentOption(3, 3535, 10605, false));
    assertThat(option(10000, settings, 6)).isEqualTo(new InstallmentOption(6, 1846, 11076, false));
    assertThat(option(10000, settings, 12))
        .isEqualTo(new InstallmentOption(12, 1005, 12060, false));
  }

  @Test
  void theTotalIsAlwaysTheInstallmentTimesTheCount() {
    for (InstallmentOption option : InstallmentPricing.options(12345, settings(12, 1, 299))) {
      if (!option.interestFree()) {
        assertThat(option.total()).isEqualTo(option.installmentAmount() * option.count());
        assertThat(option.total()).isGreaterThan(12345);
      }
    }
  }

  @Test
  void interestStartsAfterTheFreeOnes() {
    List<InstallmentOption> options = InstallmentPricing.options(10000, settings(10, 3, 299));

    assertThat(options).hasSize(10);
    assertThat(options.subList(0, 3))
        .containsExactly(
            new InstallmentOption(1, 10000, 10000, true),
            new InstallmentOption(2, 5000, 10000, true),
            new InstallmentOption(3, 3333, 10000, true));
    assertThat(options.subList(3, 10)).noneMatch(InstallmentOption::interestFree);
    assertThat(options.get(3)).isEqualTo(new InstallmentOption(4, 2690, 10760, false));
    assertThat(options.get(9)).isEqualTo(new InstallmentOption(10, 1172, 11720, false));
    assertThat(options.get(5).interestOver(10000)).isEqualTo(1076);
  }

  @Test
  void theDefaultIsTwelveInterestFree() {
    InstallmentSettings defaults = InstallmentSettings.defaults(MERCHANT, ProviderEnvironment.TEST);

    List<InstallmentOption> options = InstallmentPricing.options(10000, defaults);

    assertThat(options).hasSize(12).allMatch(InstallmentOption::interestFree);
    assertThat(options).allMatch(option -> option.total() == 10000);
    assertThat(options.getLast()).isEqualTo(new InstallmentOption(12, 833, 10000, true));
  }

  @Test
  void aZeroRateIsInterestFreeAllTheWay() {
    List<InstallmentOption> options = InstallmentPricing.options(10000, settings(6, 1, 0));

    assertThat(options).hasSize(6).allMatch(InstallmentOption::interestFree);
    assertThat(options).allMatch(option -> option.interestOver(10000) == 0);
  }

  @Test
  void interestFreeUpToTheMaximumNeverCharges() {
    List<InstallmentOption> options = InstallmentPricing.options(10000, settings(10, 10, 299));

    assertThat(options).hasSize(10).allMatch(option -> option.total() == 10000);
  }

  /**
   * R$ 5,00 per installment. Interest-free: 2000 / 4 = 500 is offered, 2000 / 5 = 400 is not. With
   * interest the computed installment counts: 2000 at 2,99% is 59.8 / (1 − v), so 2x 1045.07 →
   * 1046, 3x 706.92 → 707, 4x 537.93 → 538, and 5x 59.8 / (1 − 0.8630276485) = 436.58 → 437, under
   * the minimum.
   */
  @Test
  void anOptionUnderFiveReaisIsNotOffered() {
    assertThat(InstallmentPricing.options(2000, settings(12, 12, 0)))
        .extracting(InstallmentOption::count)
        .containsExactly(1, 2, 3, 4);
    assertThat(InstallmentPricing.options(2000, settings(12, 1, 299)))
        .extracting(InstallmentOption::installmentAmount)
        .containsExactly(2000L, 1046L, 707L, 538L);
    assertThat(InstallmentPricing.option(2000, settings(12, 1, 299), 5)).isEmpty();
  }

  /**
   * The minimum is on the computed installment, so the offer can skip a count: 1900 interest-free
   * up to 4x is 475 in 4x (out), and at 10% the 5x is 190 / (1 − 1.1^−5) = 501.22 → 502 (in); 6x is
   * 436.25 → 437 (out).
   */
  @Test
  void aPricedCountMayClearTheMinimumThatAFreeOneMissed() {
    assertThat(InstallmentPricing.options(1900, settings(12, 4, 1000)))
        .extracting(InstallmentOption::count)
        .containsExactly(1, 2, 3, 5);
  }

  /** 1x is always offered, as InstallmentPlan allows a single installment of any amount. */
  @Test
  void oneInstallmentIsOfferedEvenUnderTheMinimum() {
    assertThat(InstallmentPricing.options(300, settings(12, 12, 0)))
        .containsExactly(new InstallmentOption(1, 300, 300, true));
  }

  @Test
  void aCountOutsideTheSettingsIsNotOffered() {
    InstallmentSettings settings = settings(10, 3, 299);

    assertThat(InstallmentPricing.option(10000, settings, 11)).isEmpty();
    assertThat(InstallmentPricing.option(10000, settings, 0)).isEmpty();
    assertThat(InstallmentPricing.option(10000, settings, -1)).isEmpty();
  }
}
