package com.gateway.kernel.money;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void addsAndSubtractsInCents() {
    Money a = Money.brl(15990), b = Money.brl(10);
    assertThat(a.plus(b)).isEqualTo(Money.brl(16000));
    assertThat(a.minus(b)).isEqualTo(Money.brl(15980));
  }

  @Test
  void rejectsMixedCurrencies() {
    assertThatThrownBy(() -> Money.brl(1).plus(new Money(1, "USD")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegative() {
    assertThatThrownBy(() -> Money.brl(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.brl(1).minus(Money.brl(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void compares() {
    assertThat(Money.brl(2).greaterThan(Money.brl(1))).isTrue();
    assertThat(Money.ZERO_BRL.isZero()).isTrue();
  }
}
