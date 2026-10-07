package com.gateway.billing.order.checkout;

import com.gateway.billing.installment.InstallmentOption;
import com.gateway.billing.order.Order;
import com.gateway.payments.payment.Payment;
import java.util.List;
import java.util.Optional;

/**
 * What the payer may see: the order, its attempts and the card installment options its amount has
 * under the merchant's settings; the app decides which fields leave (the options only when the card
 * is offered). {@code invoiceTerms} is null for a standalone order.
 */
public record CheckoutView(
    Order order,
    List<Payment> attempts,
    List<InstallmentOption> installmentOptions,
    InvoiceTerms invoiceTerms) {

  /** True when the only way to pay is a new card that stays saved for the subscription. */
  public boolean savesCardForSubscription() {
    return invoiceTerms != null && invoiceTerms.savesCard();
  }

  public Optional<Payment> activeAttempt() {
    return attempts.stream().filter(attempt -> attempt.status().isActive()).findFirst();
  }
}
