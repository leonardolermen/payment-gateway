package com.gateway.payments.payment.create;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;

/**
 * A Pix charge. {@code expiresInSeconds} null means the configured default; the payer's document is
 * optional and is sent to the bank so the QR can be restricted to whoever was charged.
 */
public record CreatePixPayment(
    MerchantId merchantId,
    ProviderEnvironment environment,
    Money amount,
    String reference,
    String description,
    String customerDocument,
    Integer expiresInSeconds)
    implements CreatePaymentCommand {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }
}
