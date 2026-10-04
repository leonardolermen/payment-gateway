package com.gateway.app.api.customer.dto;

/**
 * PATCH /v1/customers/{id}. No {@code document}: it never changes, and the mapper's
 * FAIL_ON_UNKNOWN_PROPERTIES answers a body carrying one with 400 before the controller runs.
 */
public record CustomerPatchRequest(String name, String email, AddressFields address) {

  public void validate() {
    if (name == null && email == null && address == null) {
      throw new IllegalArgumentException("at least one of name, email or address is required");
    }
  }

  @Override
  public String toString() {
    return "CustomerPatchRequest[***]";
  }
}
