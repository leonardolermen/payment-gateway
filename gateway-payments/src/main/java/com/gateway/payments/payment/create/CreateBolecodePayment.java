package com.gateway.payments.payment.create;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.LocalDate;

/**
 * A registered boleto with a Pix QR on the same issue. {@code payer} is the raw {@link PayerData}:
 * {@link PayerFactory} validates it inside the flow, where the 422 names the field and no row exists
 * yet. {@code dueDate} and {@code paymentLimitDays} null mean the configured defaults.
 */
public record CreateBolecodePayment(
    MerchantId merchantId,
    ProviderEnvironment environment,
    Money amount,
    String reference,
    String description,
    PayerData payer,
    LocalDate dueDate,
    Integer paymentLimitDays) implements CreatePaymentCommand {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.BOLECODE;
  }
}
