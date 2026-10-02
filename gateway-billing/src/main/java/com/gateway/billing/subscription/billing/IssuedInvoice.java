package com.gateway.billing.subscription.billing;

import com.gateway.payments.payment.Payment;

/**
 * What one attempt on an invoice came to. {@code charged} is true only for a COMPLETED card: a Pix
 * or a boleto is issued, not paid, and a CREATED payment is doubt, not money.
 *
 * @param payment the attempt, also when declined; null only when nothing was attempted
 * @param declineCode the acquirer's code when the card was declined, else null
 */
public record IssuedInvoice(Payment payment, boolean charged, String declineCode) {

  public static IssuedInvoice notAttempted() {
    return new IssuedInvoice(null, false, null);
  }

  public boolean declined() {
    return declineCode != null;
  }

  public String paymentId() {
    return payment == null ? null : payment.id();
  }
}
