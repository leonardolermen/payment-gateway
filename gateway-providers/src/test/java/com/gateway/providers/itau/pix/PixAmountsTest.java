package com.gateway.providers.itau.pix;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;

class PixAmountsTest {
  @Test void roundTripsCents() {
    assertThat(PixAmounts.toItau(Money.brl(12345))).isEqualTo("123.45");
    assertThat(PixAmounts.toItau(Money.brl(5))).isEqualTo("0.05");
    assertThat(PixAmounts.toItau(Money.brl(100000000000L))).isEqualTo("1000000000.00");
    assertThat(PixAmounts.fromItau("123.45")).isEqualTo(Money.brl(12345));
    assertThat(PixAmounts.fromItau("0.05")).isEqualTo(Money.brl(5));
  }

  /** The Bacen pattern is \d{1,10}\.\d{2}: anything else is a bug on our side, never sent. */
  @Test void rejectsWhatBacenRejects() {
    assertThatThrownBy(() -> PixAmounts.toItau(Money.brl(0))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PixAmounts.toItau(Money.brl(1_000_000_000_001L))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PixAmounts.fromItau("1,00")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PixAmounts.fromItau("1.5")).isInstanceOf(IllegalArgumentException.class);
  }
}
