package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;

/**
 * reference/criar-pagamento-credito, Interest: "No caso de parcelamento pela loja (ByMerchant), o
 * valor mínimo da parcela precisa ser de R$5,00" (plan D5, Review Focus 4).
 */
class InstallmentPlanTest {

  @Test
  void oneByDefaultAndUpToTwelve() {
    assertThat(InstallmentPlan.of(Money.brl(100), null).count()).isEqualTo(1);
    assertThat(InstallmentPlan.of(Money.brl(6000), 12).count()).isEqualTo(12);
  }

  @Test
  void aNonExactDivisionIsFineWhileEveryInstallmentReachesTheMinimum() {
    assertThat(InstallmentPlan.of(Money.brl(1600), 3).count()).isEqualTo(3);
    assertThat(InstallmentPlan.of(Money.brl(1500), 3).count()).isEqualTo(3);
  }

  @Test
  void belowTheMinimumOrOutOfRangeIsRefused() {
    for (Object[] invalid :
        new Object[][] {
          {1000L, 3, "each installment must be at least 500 cents (1000 in 3)"},
          {1499L, 3, "each installment must be at least 500 cents (1499 in 3)"},
          {100000L, 13, "installments must be between 1 and 12"},
          {100000L, 0, "installments must be between 1 and 12"}
        }) {
      assertThatThrownBy(
              () -> InstallmentPlan.of(Money.brl((Long) invalid[0]), (Integer) invalid[1]))
          .isInstanceOf(DomainException.class)
          .satisfies(
              thrown -> {
                assertThat(((DomainException) thrown).code()).isEqualTo("INVALID_INSTALLMENTS");
                assertThat(thrown.getMessage()).isEqualTo(invalid[2]);
              });
    }
  }

  /** One installment has no minimum: R$ 1,00 à vista is a sale. */
  @Test
  void aSingleInstallmentHasNoMinimum() {
    assertThat(InstallmentPlan.of(Money.brl(100), 1).count()).isEqualTo(1);
  }
}
