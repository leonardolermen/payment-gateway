package com.gateway.app.api.card;

import com.gateway.app.api.card.dto.CardResponse;
import com.gateway.app.security.MerchantContext;
import com.gateway.payments.card.SavedCards;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling merchant's saved cards. Another merchant's card is 404 like an absent one — never 403
 * (spec §4). DELETE marks the row; the Cielo has no token deletion, so the token simply stops being
 * usable through the gateway.
 */
@RestController
@RequestMapping("/v1/cards")
public class CardsController {
  private final SavedCards cards;

  public CardsController(SavedCards cards) {
    this.cards = cards;
  }

  @GetMapping("/{id}")
  public CardResponse get(@PathVariable String id) {
    return CardResponse.from(cards.get(MerchantContext.current().merchantId(), id));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    cards.delete(MerchantContext.current().merchantId(), id);
  }
}
