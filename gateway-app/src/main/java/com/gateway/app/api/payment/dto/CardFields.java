package com.gateway.app.api.payment.dto;

import com.gateway.kernel.provider.card.CardData;
import com.gateway.payments.payment.create.CardDataFactory;
import java.time.YearMonth;

/**
 * The {@code card} object of a CARD create, exactly as sent. Lives only until {@link #toCardData};
 * toString is overridden because a record's would print the number and the CVV into the first log
 * line or exception that touched the request.
 */
public record CardFields(String number, String holder, String expiry, String cvv, String brand) {

  CardData toCardData(YearMonth currentMonth) {
    return CardDataFactory.from(number, holder, expiry, cvv, brand, currentMonth);
  }

  @Override
  public String toString() {
    return "CardFields[***]";
  }
}
