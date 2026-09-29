package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** The 422 names the field in the API's own spelling (card.number …); the value never appears. */
class CardDataFactoryTest {
  static final YearMonth NOW = YearMonth.of(2026, 9);

  @Test
  void eachFieldIsNamedTheWayTheMerchantSentIt() {
    assertCardInvalid(
        () -> CardDataFactory.from("4024007153763192", "JOAO", "12/2030", "123", null, NOW),
        "card.number must pass the Luhn check");
    assertCardInvalid(
        () -> CardDataFactory.from("4024007153763171", "JOAO 2", "12/2030", "123", null, NOW),
        "card.holder must contain only letters and spaces");
    assertCardInvalid(
        () -> CardDataFactory.from("4024007153763171", "JOAO", "08/2026", "123", null, NOW),
        "card.expiry must not be in the past");
    assertCardInvalid(
        () -> CardDataFactory.from("4024007153763171", "JOAO", "12/2030", "12", null, NOW),
        "card.cvv must be 3 digits");
    assertCardInvalid(
        () -> CardDataFactory.from("4024007153763171", "JOAO", "12/2030", "123", "AMEX", NOW),
        "card.brand does not match the card number (VISA)");
  }

  @Test
  void aValidCardBuilds() {
    assertThat(
            CardDataFactory.from("4024 0071 5376 3171", "JOAO", "12/2030", "123", null, NOW)
                .last4())
        .isEqualTo("3171");
  }

  /** Plan D3: the Cielo requires SecurityCode with a CardToken. */
  @Test
  void aSavedCardChargeNeedsTheCvv() {
    assertCardInvalid(
        () -> CardDataFactory.securityCodeForSavedCard(null), "cvv is required with card_id");
    assertCardInvalid(
        () -> CardDataFactory.securityCodeForSavedCard("12a"), "cvv must be 3 or 4 digits");
    assertThat(CardDataFactory.securityCodeForSavedCard("1234").reveal()).isEqualTo("1234");
  }

  static void assertCardInvalid(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
    assertThatThrownBy(call)
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("CARD_INVALID");
              assertThat(thrown.getMessage()).isEqualTo(message).doesNotContain("4024007153763192");
            });
  }
}
