package com.gateway.app.api.payment.dto;

import com.gateway.kernel.money.Money;

/**
 * The amount as the merchant asked for it, with the two messages this API has always answered.
 *
 * <p>{@link Money} is not enough on its own: it accepts zero and the API does not, and it accepts any
 * three-letter currency while the gateway only settles BRL. Shared by both request shapes so the rule
 * is written once.
 */
final class RequestedAmount {

  private RequestedAmount() {}

  static Money of(Long amount, String currency) {
    // Boxed on purpose: a missing amount is a 400, not a silent charge of zero.
    if (amount == null || amount <= 0) {
      throw new IllegalArgumentException("amount must be a positive number of cents");
    }
    if (!"BRL".equals(currency)) {
      throw new IllegalArgumentException("currency must be BRL");
    }

    return new Money(amount, currency);
  }
}
