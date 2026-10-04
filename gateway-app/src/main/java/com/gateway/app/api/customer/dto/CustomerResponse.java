package com.gateway.app.api.customer.dto;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.DocumentMask;
import java.time.Instant;

/** The document is masked: a response shows enough to recognise it, not enough to reuse it. */
public record CustomerResponse(
    String id,
    String name,
    String document,
    String email,
    AddressFields address,
    Instant createdAt,
    Instant updatedAt) {

  public static CustomerResponse from(Customer customer) {
    return new CustomerResponse(
        customer.id(),
        customer.name().value(),
        DocumentMask.mask(customer.document()),
        customer.email(),
        AddressFields.from(customer.address()),
        customer.createdAt(),
        customer.updatedAt());
  }
}
