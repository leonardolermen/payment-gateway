package com.gateway.billing.customer;

import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import java.util.function.Supplier;

/** The Bolecode payer's address, validated once here and never again. */
public record CustomerAddress(String street, String district, String city, Uf state, ZipCode zip) {

  /** Exactly as the merchant sent it; {@link #of} is the door. */
  public record Raw(String street, String district, String city, String state, String zip) {}

  public static CustomerAddress of(Raw raw) {
    requireText(raw.street(), "street");
    requireText(raw.district(), "district");
    requireText(raw.city(), "city");

    return new CustomerAddress(
        raw.street(),
        raw.district(),
        raw.city(),
        field("state", () -> Uf.of(raw.state())),
        field("zip", () -> ZipCode.of(raw.zip())));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new DomainException("CUSTOMER_INVALID", "customer.address." + name + " is required");
    }
  }

  private static <T> T field(String name, Supplier<T> parse) {
    try {
      return parse.get();
    } catch (InvalidValue invalid) {
      throw new DomainException(
          "CUSTOMER_INVALID", "customer.address." + name + " " + invalid.reason());
    }
  }
}
