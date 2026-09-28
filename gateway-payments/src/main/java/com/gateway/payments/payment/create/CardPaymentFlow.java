package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.Payment;

/**
 * Creating a card payment. Until the flow lands (Task 8) a CARD create is refused as a method not
 * supported: CARD must exist in the enum now, for the provider module to declare it, and
 * PaymentFlows refuses to start with a method that has no flow — which is the guard worth keeping.
 */
public class CardPaymentFlow implements PaymentFlow {
  public static final String PROVIDER = "CIELO";

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    throw new DomainException("METHOD_NOT_SUPPORTED", "CARD payments are not enabled yet");
  }
}
