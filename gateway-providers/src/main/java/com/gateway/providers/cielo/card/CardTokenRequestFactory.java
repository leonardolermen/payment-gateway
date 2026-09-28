package com.gateway.providers.cielo.card;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.providers.cielo.CieloText;
import com.gateway.providers.cielo.card.dto.CardTokenRequest;
import com.gateway.providers.cielo.sale.CieloBrands;

/**
 * CardData → POST /1/card/. With SaleRequestFactory, the only two places that reveal a card number
 * (spec §7); this one is not reached by payments in this phase (plan C13).
 */
public final class CardTokenRequestFactory {
  private CardTokenRequestFactory() {}

  public static CardTokenRequest from(CardData card, PersonName customerName) {
    return new CardTokenRequest(
        CieloText.customerName(customerName),
        card.number().reveal(),
        CieloText.holder(card.holder()),
        card.expiry().formatted(),
        CieloBrands.nameOf(card.brand()));
  }
}
