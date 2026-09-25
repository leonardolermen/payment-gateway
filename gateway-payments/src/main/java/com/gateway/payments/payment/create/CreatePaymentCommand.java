package com.gateway.payments.payment.create;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;

/**
 * What the edge asks for, already in the domain's vocabulary. Sealed on purpose: a third method must
 * not be addable without the compiler pointing at everything that has to learn about it.
 *
 * <p>{@code environment} is here and never a request field: it comes from the API key, so a TEST key
 * cannot reach a merchant's LIVE bank credential no matter what the body says.
 */
public sealed interface CreatePaymentCommand permits CreatePixPayment, CreateBolecodePayment {

  MerchantId merchantId();

  ProviderEnvironment environment();

  Money amount();

  String reference();

  String description();

  PaymentMethod method();
}
