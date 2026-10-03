package com.gateway.app.api.order.dto;

import com.gateway.app.api.payment.dto.CardFields;
import com.gateway.billing.order.AttemptRequest;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * {@code card} or {@code card_id} with its {@code cvv}, never both, with the same messages as POST
 * /v1/payments. The card becomes a {@code CardData} here, where the body is read, so nothing
 * downstream carries the raw number (spec 2026-09-28 §6.1).
 */
public record CardAttemptBody(
    CardFields card,
    String cardId,
    String cvv,
    Integer installments,
    Boolean capture,
    String softDescriptor,
    Boolean saveCard)
    implements OrderAttemptRequest {
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  @Override
  public AttemptRequest toAttempt() {
    if ((card == null) == (cardId == null)) {
      throw new IllegalArgumentException("exactly one of card and card_id is required");
    }
    if (cardId != null && Boolean.TRUE.equals(saveCard)) {
      throw new IllegalArgumentException("save_card applies to a new card, not to card_id");
    }

    return new AttemptRequest.CardAttempt(choice(), installments, capture, softDescriptor);
  }

  private CardChoice choice() {
    if (card != null) {
      return new CardChoice.NewCard(
          card.toCardData(YearMonth.now(SAO_PAULO)), Boolean.TRUE.equals(saveCard));
    }

    return new CardChoice.SavedCardChoice(cardId, CardDataFactory.securityCodeForSavedCard(cvv));
  }

  /** The record's own would print the CVV of a card_id charge. */
  @Override
  public String toString() {
    return "CardAttemptBody[cardId=" + cardId + ", card=***]";
  }
}
