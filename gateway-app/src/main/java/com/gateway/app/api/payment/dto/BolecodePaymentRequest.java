package com.gateway.app.api.payment.dto;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import java.time.LocalDate;

/**
 * A registered boleto with a Pix QR on the same issue. It has no {@code expires_in}: a bolecode
 * expires on its payment limit date and nowhere else, which is why that field belongs to the other
 * shape.
 *
 * <p>The payer is not validated here. The domain answers CUSTOMER_REQUIRED naming the field, and it
 * does so before a row exists — see {@code PayerFactory}.
 */
public record BolecodePaymentRequest(
    Long amount,
    String currency,
    String reference,
    String description,
    Customer customer,
    LocalDate dueDate,
    Integer paymentLimitDays)
    implements CreatePaymentRequest {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.BOLECODE;
  }

  @Override
  public void validate() {
    RequestedAmount.of(amount, currency);

    if (paymentLimitDays != null && paymentLimitDays < 0) {
      throw new IllegalArgumentException("payment_limit_days must not be negative");
    }
  }

  @Override
  public CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment) {
    return new CreateBolecodePayment(
        merchantId,
        environment,
        RequestedAmount.of(amount, currency),
        reference,
        description,
        customer == null ? null : customer.toPayerData(),
        dueDate,
        paymentLimitDays);
  }
}
