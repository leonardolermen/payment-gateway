package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.security.Secret;

/**
 * {@code card} or {@code card_id}, never both (spec §9). Sealed so the flow's switch names the two
 * and nothing else. {@code save} is the merchant's {@code save_card}.
 */
public sealed interface CardChoice
    permits CardChoice.NewCard, CardChoice.SavedCardChoice, CardChoice.RecurringCard {

  record NewCard(CardData card, boolean save) implements CardChoice {}

  /** The CVV the payer typed for this charge: the Cielo requires it with a token (plan D3). */
  record SavedCardChoice(String cardId, Secret securityCode) implements CardChoice {}

  /**
   * A stored card charged with no CVV, for a subscription cycle. Only billing's jobs build it: the
   * API's {@code CardPaymentRequest.toCommand} cannot produce one, because the flow receives no
   * {@link com.gateway.payments.payment.EventSource} to refuse it with, and a merchant-initiated
   * charge without the CVV must stay a recurring-only path.
   */
  record RecurringCard(String cardId) implements CardChoice {}
}
