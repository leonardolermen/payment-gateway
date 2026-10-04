package com.gateway.billing.customer;

import com.gateway.kernel.errors.DomainException;

/** 409 at the edge, with the existing id so the merchant can use it instead of retrying. */
public class CustomerExistsException extends DomainException {
  private final String customerId;

  public CustomerExistsException(String customerId) {
    super("CUSTOMER_EXISTS", "a customer with this document already exists: " + customerId);
    this.customerId = customerId;
  }

  public String customerId() {
    return customerId;
  }
}
