package com.gateway.app.api.order.dto;

import com.gateway.app.api.customer.dto.CustomerRequest;
import com.gateway.app.api.payment.dto.RequestedAmount;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderPayer;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;

/**
 * POST /v1/orders: a registered {@code customer_id} or an inline {@code customer} in the shape of
 * POST /v1/customers, exactly one of them.
 */
public record CreateOrderRequest(
    Long amount,
    String currency,
    String reference,
    String description,
    String customerId,
    CustomerRequest customer,
    Instant expiresAt) {

  public Order toOrder(MerchantId merchantId, ProviderEnvironment environment, Clock clock) {
    if ((customerId == null) == (customer == null)) {
      throw new IllegalArgumentException("exactly one of customer_id or customer is required");
    }

    OrderPayer payer =
        customer == null ? null : customer.toOrderPayer(merchantId, environment, clock);

    return OrderFactory.standalone(
        merchantId,
        environment,
        RequestedAmount.of(amount, currency),
        reference,
        description,
        customerId,
        payer,
        expiresAt,
        null,
        clock);
  }
}
