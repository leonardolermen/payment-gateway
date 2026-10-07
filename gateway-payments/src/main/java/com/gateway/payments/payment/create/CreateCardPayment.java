package com.gateway.payments.payment.create;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;

/**
 * A credit card charge (spec 2026-09-28 §6). The card arrives already a {@code CardData} — built at
 * the edge by {@link CardDataFactory}, where the 422 names {@code card.number} — or as a saved
 * {@code card_id} the flow resolves. {@code installments} null is 1, {@code capture} null is true.
 *
 * <p>{@code interestAmount} is the merchant's interest already inside {@code amount}, in cents: an
 * order attempt is priced by billing (spec 2026-10-07 §4) and charges the total. Null is 0, which
 * is every other path: the direct create, where the merchant chooses the amount, and a subscription
 * cycle.
 */
public record CreateCardPayment(
    MerchantId merchantId,
    ProviderEnvironment environment,
    Money amount,
    String reference,
    String description,
    CardChoice card,
    Integer installments,
    Long interestAmount,
    Boolean capture,
    String softDescriptor,
    CardCustomerData customer,
    String orderId)
    implements CreatePaymentCommand {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  public long interest() {
    return interestAmount == null ? 0 : interestAmount;
  }

  public boolean captures() {
    return capture == null || capture;
  }
}
