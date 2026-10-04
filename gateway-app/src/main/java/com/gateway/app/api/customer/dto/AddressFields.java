package com.gateway.app.api.customer.dto;

import com.gateway.billing.customer.CustomerAddress;

/** The customer's address on the wire, both ways: plain strings, checked by CustomerAddress. */
public record AddressFields(String street, String district, String city, String state, String zip) {

  public CustomerAddress.Raw toRaw() {
    return new CustomerAddress.Raw(street, district, city, state, zip);
  }

  public static AddressFields from(CustomerAddress address) {
    if (address == null) {
      return null;
    }

    return new AddressFields(
        address.street(),
        address.district(),
        address.city(),
        address.state().value(),
        address.zip().digits());
  }
}
