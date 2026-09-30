package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.security.Secret;

/**
 * {@code card} or {@code card_id}, never both (spec §9). Sealed so the flow's switch names the two
 * and nothing else. {@code save} is the merchant's {@code save_card}.
 */
public sealed interface CardChoice permits CardChoice.NewCard, CardChoice.SavedCardChoice {

  record NewCard(CardData card, boolean save) implements CardChoice {}

  /** The CVV the payer typed for this charge: the Cielo requires it with a token (plan D3). */
  record SavedCardChoice(String cardId, Secret securityCode) implements CardChoice {}
}
