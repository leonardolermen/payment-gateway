package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;

class BoletoAmountsTest {
  @Test
  void roundTrips() {
    assertThat(BoletoAmounts.toItau(Money.brl(123456))).isEqualTo("1234.56");
    assertThat(BoletoAmounts.toItau(Money.brl(5))).isEqualTo("0.05");
    assertThat(BoletoAmounts.fromItau("2100.00")).isEqualTo(Money.brl(210000));
    assertThat(BoletoAmounts.fromItau("0.05")).isEqualTo(Money.brl(5));
  }

  /**
   * The boleto pattern is ^\d+\.\d{2}$ with 15 integer digits; anything else is a bug on our side.
   */
  @Test
  void rejectsWhatTheBankRejects() {
    assertThatThrownBy(() -> BoletoAmounts.toItau(Money.brl(0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.toItau(new Money(1, "USD")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.fromItau("1,00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.fromItau("1.5"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.fromItau("00000000000001000"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
