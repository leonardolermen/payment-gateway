package com.gateway.app.api.order.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.order.AttemptRequest;
import com.gateway.payments.payment.create.CardChoice;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The attempt body is the payment body without what the order already decided: amount, currency and
 * payer. Sending one of those is refused by the deserialiser, not silently ignored: an amount that
 * differed from the order's would otherwise look accepted.
 */
class OrderAttemptRequestTest {
  /** Same two settings as application.yml: snake_case, and an unknown property is an error. */
  private final JsonMapper mapper =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private OrderAttemptRequest read(String json) {
    return mapper.readValue(json, OrderAttemptRequest.class);
  }

  @Test
  void aPixBodyBecomesAPixAttempt() {
    AttemptRequest attempt = read("{\"method\":\"PIX\",\"expires_in\":600}").toAttempt();

    assertThat(attempt).isEqualTo(new AttemptRequest.PixAttempt(600));
  }

  @Test
  void aBolecodeBodyBecomesABolecodeAttempt() {
    AttemptRequest attempt =
        read("{\"method\":\"BOLECODE\",\"due_date\":\"2026-11-01\",\"payment_limit_days\":5}")
            .toAttempt();

    assertThat(attempt).isInstanceOf(AttemptRequest.BolecodeAttempt.class);
  }

  @Test
  void aCardIdWithCvvIsASavedCardChoice() {
    AttemptRequest attempt =
        read("{\"method\":\"CARD\",\"card_id\":\"card_1\",\"cvv\":\"123\",\"capture\":true}")
            .toAttempt();

    assertThat(((AttemptRequest.CardAttempt) attempt).card())
        .isInstanceOf(CardChoice.SavedCardChoice.class);
  }

  @Test
  void aCardBodyWithBothCardAndCardIdIsRefused() {
    OrderAttemptRequest request =
        read(
            "{\"method\":\"CARD\",\"card_id\":\"card_1\",\"cvv\":\"123\","
                + "\"card\":{\"number\":\"4024007153763171\",\"holder\":\"ANA\","
                + "\"expiry\":\"12/2030\",\"cvv\":\"123\",\"brand\":\"VISA\"}}");

    assertThatThrownBy(request::toAttempt)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("exactly one of card and card_id is required");
  }

  @Test
  void aCardBodyWithNeitherIsRefused() {
    OrderAttemptRequest request = read("{\"method\":\"CARD\"}");

    assertThatThrownBy(request::toAttempt).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void saveCardWithACardIdIsRefused() {
    OrderAttemptRequest request =
        read("{\"method\":\"CARD\",\"card_id\":\"card_1\",\"cvv\":\"123\",\"save_card\":true}");

    assertThatThrownBy(request::toAttempt)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("save_card applies to a new card, not to card_id");
  }

  @Test
  void theOrdersOwnFieldsAreRefused() {
    assertThatThrownBy(() -> read("{\"method\":\"PIX\",\"amount\":1000}"))
        .isInstanceOf(JacksonException.class);
    assertThatThrownBy(() -> read("{\"method\":\"PIX\",\"customer\":{\"name\":\"Ana\"}}"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aNonPositiveExpiryIsRefused() {
    OrderAttemptRequest request = read("{\"method\":\"PIX\",\"expires_in\":0}");

    assertThatThrownBy(request::toAttempt)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("expires_in must be positive seconds");
  }

  @Test
  void theCardNeverReachesToString() {
    OrderAttemptRequest request =
        read(
            "{\"method\":\"CARD\",\"card\":{\"number\":\"4024007153763171\",\"holder\":\"ANA\","
                + "\"expiry\":\"12/2030\",\"cvv\":\"987\",\"brand\":\"VISA\"}}");

    assertThat(request.toString()).doesNotContain("4024007153763171").doesNotContain("987");
  }
}
