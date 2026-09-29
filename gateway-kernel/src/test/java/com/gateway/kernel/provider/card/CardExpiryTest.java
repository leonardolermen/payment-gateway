package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CardExpiryTest {
  static final YearMonth SEPTEMBER_2026 = YearMonth.of(2026, 9);

  @Test
  void parsesMonthSlashYearAndFormatsItBack() {
    CardExpiry expiry = CardExpiry.of("12/2030", SEPTEMBER_2026);

    assertThat(expiry.value()).isEqualTo(YearMonth.of(2030, 12));
    assertThat(expiry.formatted()).isEqualTo("12/2030");
  }

  /** Review Focus 3: a card is good through the last day of its printed month. */
  @Test
  void theCurrentMonthIsStillValid() {
    assertThat(CardExpiry.of("09/2026", SEPTEMBER_2026).value()).isEqualTo(SEPTEMBER_2026);
    assertThatThrownBy(() -> CardExpiry.of("08/2026", SEPTEMBER_2026))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("must not be in the past");
  }

  @Test
  void refusesAnythingButMonthSlashFourDigitYear() {
    for (String invalid :
        new String[] {null, "", "12/30", "13/2030", "00/2030", "2030-12", "1/2030"}) {
      assertThatThrownBy(() -> CardExpiry.of(invalid, SEPTEMBER_2026))
          .as("expiry %s", invalid)
          .isInstanceOf(InvalidCardValue.class)
          .extracting(thrown -> ((InvalidCardValue) thrown).field())
          .isEqualTo("expiry");
    }
  }
}
