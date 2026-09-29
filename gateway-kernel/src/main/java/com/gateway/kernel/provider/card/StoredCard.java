package com.gateway.kernel.provider.card;

import java.time.YearMonth;

/** A card stored at the acquirer by {@code POST /1/card/}. The token is never printed. */
public record StoredCard(String token, CardBrand brand, String last4, YearMonth expiry) {

  @Override
  public String toString() {
    return "StoredCard[" + brand + " ****" + last4 + ", token=***]";
  }
}
