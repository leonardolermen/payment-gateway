package com.gateway.payments.payment.create;

import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.party.Address;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.Payer;
import com.gateway.kernel.party.PersonName;
import java.util.function.Supplier;

/**
 * Builds the validated payer from what the merchant sent, naming each field in the API's own
 * spelling.
 *
 * <p>In the domain and not at the edge for two reasons: the answer is a 422 that names the field,
 * and no row exists yet when it is refused.
 *
 * <p>The value objects own the formats (see {@code kernel/party} and {@code kernel/address}); this
 * class owns only the two things they cannot know — that the field is called {@code
 * customer.address.zip} out on the wire, and that the merchant-facing code is {@code
 * CUSTOMER_REQUIRED}.
 */
public final class PayerFactory {
  private static final String CODE = "CUSTOMER_REQUIRED";

  private PayerFactory() {}

  public static Payer from(PayerData data) {
    if (data == null) {
      throw new DomainException(CODE, "customer is required for a BOLECODE payment");
    }
    if (data.address() == null) {
      throw new DomainException(CODE, "customer.address is required");
    }

    PayerData.AddressData address = data.address();

    return new Payer(
        at("customer.name", () -> PersonName.of(data.name())),
        at("customer.document", () -> Document.of(data.document())),
        new Address(
            required("customer.address.street", address.street()),
            required("customer.address.district", address.district()),
            required("customer.address.city", address.city()),
            at("customer.address.state", () -> Uf.of(address.state())),
            at("customer.address.zip", () -> ZipCode.of(address.zip()))));
  }

  /** The value object knows the reason; only the caller knows the field's name out on the wire. */
  private static <T> T at(String field, Supplier<T> build) {
    try {
      return build.get();
    } catch (InvalidValue e) {
      throw new DomainException(CODE, field + " " + e.reason());
    }
  }

  private static String required(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new DomainException(CODE, field + " is required");
    }

    return value;
  }
}
