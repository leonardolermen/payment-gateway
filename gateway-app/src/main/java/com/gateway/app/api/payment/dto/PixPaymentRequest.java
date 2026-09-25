package com.gateway.app.api.payment.dto;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.gateway.payments.payment.create.CreatePixPayment;

/**
 * A Pix charge. {@code expires_in} is the field that is Pix's alone; {@code customer} is optional and
 * only its document is used, to restrict the QR to whoever was charged.
 *
 * <p>A field belonging to the other method is refused by the deserialiser, not by an if: the mapper has
 * FAIL_ON_UNKNOWN_PROPERTIES on (application.yml), because silently swallowing a due_date sent here is
 * the very mistake this shape exists to catch.
 */
public record PixPaymentRequest(
    Long amount, String currency, String reference, String description, Customer customer, Integer expiresIn)
    implements CreatePaymentRequest {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }

  @Override
  public void validate() {
    RequestedAmount.of(amount, currency);

    if (expiresIn != null && expiresIn <= 0) {
      throw new IllegalArgumentException("expires_in must be positive seconds");
    }
  }

  @Override
  public CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment) {
    return new CreatePixPayment(
        merchantId,
        environment,
        RequestedAmount.of(amount, currency),
        reference,
        description,
        customer == null ? null : customer.document(),
        expiresIn);
  }
}
