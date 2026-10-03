package com.gateway.app.api.customer.dto;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.order.OrderPayer;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;

/**
 * POST /v1/customers, and the inline {@code customer} of POST /v1/orders. Copied, not validated:
 * {@link CustomerFactory} and the value objects own the 422 and name the field as the client sent
 * it ({@code customer.address.zip}).
 */
public record CustomerRequest(String name, String document, String email, AddressFields address) {

  public Customer toCustomer(MerchantId merchantId, ProviderEnvironment environment, Clock clock) {
    return CustomerFactory.fromRequest(
        merchantId,
        environment,
        name,
        document,
        email,
        address == null ? null : address.toRaw(),
        clock);
  }

  /**
   * Parsed through the customer factory, not field by field: an order's inline payer must answer
   * the same CUSTOMER_INVALID messages as a registered customer, and that factory is where they
   * live. The parsed customer is never saved.
   */
  public OrderPayer toOrderPayer(
      MerchantId merchantId, ProviderEnvironment environment, Clock clock) {
    Customer parsed = toCustomer(merchantId, environment, clock);

    return new OrderPayer(parsed.name(), parsed.document(), parsed.email(), parsed.address());
  }

  /** The record's own would print the document and the e-mail into a log line. */
  @Override
  public String toString() {
    return "CustomerRequest[***]";
  }
}
