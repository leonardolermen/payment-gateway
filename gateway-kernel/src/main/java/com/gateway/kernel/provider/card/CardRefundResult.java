package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;

/**
 * The answer to a void with an amount. Synchronous at the Cielo. Both VOIDED and REFUNDED mean the
 * money went back: a void on the day of the sale answers 10 even after capture, and only after
 * 23h59 does it answer 11 (reference/payment-status; plan D2).
 */
public record CardRefundResult(
    CardStatus status, Money refundedAmount, String returnCode, String returnMessage) {

  public boolean completed() {
    return status == CardStatus.VOIDED || status == CardStatus.REFUNDED;
  }
}
