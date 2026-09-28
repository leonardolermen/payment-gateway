package com.gateway.payments.refund;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import java.util.List;

/**
 * How much of a payment a new refund may take. One rule for Pix and card: Pix and card refunds had
 * grown two copies of it, and a fix landing in one copy only would let one method refund more than
 * the other allows.
 */
public final class RefundReservation {
  private RefundReservation() {}

  /**
   * Reserved = everything not FAILED: a PROCESSING or UNKNOWN refund is money the bank may still
   * send back, and counting only COMPLETED ones would let two quick requests exceed the original.
   *
   * @param capName how the cap is named in the error ("payment amount" for Pix, "paid amount" for a
   *     card, where a partial capture makes it smaller) — the message is contract
   * @return the amount to refund: {@code requestedOrNull}, or everything not yet reserved
   */
  public static Money reserve(
      List<Refund> existing, Money cap, Money requestedOrNull, String capName) {
    Money reserved = cap.minus(cap);
    for (Refund refund : existing) {
      if (refund.state() != RefundState.FAILED) {
        reserved = reserved.plus(refund.amount());
      }
    }

    Money remaining = reserved.greaterThan(cap) ? cap.minus(cap) : cap.minus(reserved);
    Money amount = requestedOrNull == null ? remaining : requestedOrNull;

    if (amount.isZero() || amount.greaterThan(remaining)) {
      throw new DomainException(
          "REFUND_EXCEEDS_AMOUNT",
          "refunds would total more than the " + capName + "; remaining " + remaining.cents());
    }

    return amount;
  }
}
