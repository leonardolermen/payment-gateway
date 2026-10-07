package com.gateway.billing.installment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import org.junit.jupiter.api.Test;

/** Spec 2026-10-07 §2: every range error is an IllegalArgumentException, the API's 400. */
class InstallmentSettingsTest {
  static InstallmentSettings settings(int max, int freeUpTo, int bps) {
    return new InstallmentSettings(
        MerchantId.next(), ProviderEnvironment.TEST, max, freeUpTo, bps, null);
  }

  @Test
  void theEdgesAreAccepted() {
    assertThatCode(() -> settings(1, 1, 0)).doesNotThrowAnyException();
    assertThatCode(() -> settings(12, 12, 1000)).doesNotThrowAnyException();
  }

  @Test
  void outOfRangeIsRefusedNamingTheField() {
    assertThatThrownBy(() -> settings(0, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("max_installments must be between 1 and 12");
    assertThatThrownBy(() -> settings(13, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("max_installments must be between 1 and 12");
    assertThatThrownBy(() -> settings(6, 7, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("interest_free_up_to must be between 1 and max_installments (6)");
    assertThatThrownBy(() -> settings(6, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("interest_free_up_to");
    assertThatThrownBy(() -> settings(6, 1, 1001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("monthly_rate_bps must be between 0 and 1000");
    assertThatThrownBy(() -> settings(6, 1, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("monthly_rate_bps");
  }
}
