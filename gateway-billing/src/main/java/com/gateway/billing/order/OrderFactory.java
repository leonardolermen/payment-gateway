package com.gateway.billing.order;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

public final class OrderFactory {
  private OrderFactory() {}

  public static Order standalone(
      MerchantId merchantId,
      ProviderEnvironment environment,
      Money amount,
      String reference,
      String description,
      String customerId,
      OrderPayer payer,
      Instant expiresAt,
      String checkoutTokenHash,
      Clock clock) {
    if ((customerId == null) == (payer == null)) {
      throw new IllegalArgumentException("exactly one of customer_id or customer is required");
    }
    if (amount.cents() <= 0) {
      throw new IllegalArgumentException("amount must be a positive number of cents");
    }

    return new Order(
        Ulid.next(),
        merchantId,
        environment,
        customerId,
        payer,
        amount,
        reference,
        description,
        expiresAt,
        null,
        null,
        null,
        null,
        checkoutTokenHash,
        clock.instant());
  }

  public static Order invoice(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String customerId,
      Money amount,
      String subscriptionId,
      int invoiceNumber,
      LocalDate periodStart,
      LocalDate periodEnd,
      Instant expiresAt,
      String checkoutTokenHash,
      Clock clock) {
    return new Order(
        Ulid.next(),
        merchantId,
        environment,
        customerId,
        null,
        amount,
        "sub:" + subscriptionId + ":" + invoiceNumber,
        null,
        expiresAt,
        subscriptionId,
        invoiceNumber,
        periodStart,
        periodEnd,
        checkoutTokenHash,
        clock.instant());
  }
}
