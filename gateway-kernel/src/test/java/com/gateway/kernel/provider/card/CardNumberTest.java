package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CardNumberTest {

  /**
   * Luhn-valid and ending in 1, which the sandbox approves (reference/credito-sandbox decides by
   * the last digit). The page's own example, 4024.0071.5376.3191, fails the Luhn check (plan D20).
   */
  static final String SANDBOX_VISA = "4024007153763171";

  @Test
  void keepsTheDigitsAndMasksEverythingElse() {
    CardNumber number = CardNumber.of(SANDBOX_VISA);

    assertThat(number.reveal()).isEqualTo(SANDBOX_VISA);
    assertThat(number.last4()).isEqualTo("3171");
    assertThat(number.first6()).isEqualTo("402400");
    assertThat(number.masked()).isEqualTo("402400******3171");
    assertThat(number.toString()).isEqualTo("****3171").doesNotContain("402400");
  }

  /** Review Focus 1: a checkout form hands over what the payer typed, grouping and all. */
  @Test
  void acceptsSpacesAndDashes() {
    assertThat(CardNumber.of("4024 0071 5376 3171").reveal()).isEqualTo(SANDBOX_VISA);
    assertThat(CardNumber.of("4024-0071-5376-3171").reveal()).isEqualTo(SANDBOX_VISA);
    assertThat(CardNumber.of(" 4024 0071-5376 3171 ").last4()).isEqualTo("3171");
  }

  @Test
  void refusesAFailedLuhnCheck() {
    assertThatThrownBy(() -> CardNumber.of("4024007153763192"))
        .isInstanceOf(InvalidCardValue.class)
        .satisfies(
            thrown -> {
              InvalidCardValue invalid = (InvalidCardValue) thrown;
              assertThat(invalid.field()).isEqualTo("number");
              assertThat(invalid.reason()).isEqualTo("must pass the Luhn check");
              assertThat(invalid.getMessage()).doesNotContain("4024007153763192");
            });
  }

  @Test
  void refusesWrongLengthsLettersAndNothing() {
    for (String invalid :
        new String[] {
          null, "", "   ", "411111111111", "41111111111111111111", "4111x11111111111"
        }) {
      assertThatThrownBy(() -> CardNumber.of(invalid))
          .as("number %s", invalid)
          .isInstanceOf(InvalidCardValue.class)
          .extracting(thrown -> ((InvalidCardValue) thrown).reason())
          .isEqualTo("must be 13 to 19 digits");
    }
  }

  @Test
  void acceptsShortAndLongNumbersThatPassLuhn() {
    assertThat(CardNumber.of("4222222222222").last4()).isEqualTo("2222");
    assertThat(CardNumber.of("6011000990139424").last4()).isEqualTo("9424");
  }
}
