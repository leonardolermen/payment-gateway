package com.gateway.app.api.payment.dto;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The shape of a create, chosen by {@code method}: PIX takes {@code expires_in}; BOLECODE takes a
 * complete {@code customer}, {@code due_date} and {@code payment_limit_days}.
 *
 * <p>Each subtype declares only its own fields, so a field belonging to the other method is refused by
 * the deserialiser instead of by a chain of string comparisons — and each one validates only its own
 * rules, with no {@code if} asking which method it is.
 *
 * <p>The environment is never a field here: it is the API key's, so a TEST key cannot reach a
 * merchant's LIVE bank credential no matter what the body says.
 */
// visible is left off on purpose: the resolver consumes `method` and does not pass it down, so neither
// record has to declare a component it already answers from its own type.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "method")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PixPaymentRequest.class, name = "PIX"),
  @JsonSubTypes.Type(value = BolecodePaymentRequest.class, name = "BOLECODE")
})
public sealed interface CreatePaymentRequest permits PixPaymentRequest, BolecodePaymentRequest {

  PaymentMethod method();

  /** Only this body's own rules. Whatever needs the bank or the payer's shape belongs to the domain. */
  void validate();

  CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment);
}
