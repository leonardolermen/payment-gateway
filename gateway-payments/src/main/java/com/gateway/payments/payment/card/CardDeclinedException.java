package com.gateway.payments.payment.card;

import com.gateway.kernel.errors.DomainException;

/**
 * The issuer said no. A 402 at the edge with {@code decline_code} (spec §9). The message is fixed:
 * the issuer's ReturnMessage never reaches a merchant, and a fixed text is also what the
 * idempotency store may replay for 24 h.
 */
public class CardDeclinedException extends DomainException {
  private final String paymentId;
  private final String declineCode;

  public CardDeclinedException(String paymentId, String declineCode) {
    super("CARD_DECLINED", "The card was declined.");
    this.paymentId = paymentId;
    this.declineCode = declineCode;
  }

  public String paymentId() {
    return paymentId;
  }

  public String declineCode() {
    return declineCode;
  }
}
