package com.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BillingPropertiesTest {

  @Test
  void defaultsFillEveryMissingValue() {
    BillingProperties properties = new BillingProperties(null, 0, null);

    assertThat(properties.dunningRetryDays()).containsExactly(1, 3, 7);
    assertThat(properties.billingHour()).isEqualTo(3);
    assertThat(properties.cardRecurringEnabled()).isTrue();
  }

  @Test
  void retryDaysMustBeAscendingAndPositive() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new BillingProperties(List.of(3, 1), 3, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
