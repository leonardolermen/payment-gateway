package com.gateway.billing.customer;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

public final class CustomerFactory {
  private CustomerFactory() {}

  public static Customer fromRequest(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String name,
      String document,
      String email,
      CustomerAddress.Raw address,
      Clock clock) {
    PersonName personName = field("name", () -> PersonName.of(name));
    Document parsedDocument = field("document", () -> Document.of(document));
    CustomerAddress parsedAddress = address == null ? null : CustomerAddress.of(address);
    Instant now = clock.instant();

    return new Customer(
        Ulid.next(),
        merchantId,
        environment,
        personName,
        parsedDocument,
        blankToNull(email),
        parsedAddress,
        1L,
        now,
        now,
        null);
  }

  static <T> T field(String name, Supplier<T> parse) {
    try {
      return parse.get();
    } catch (InvalidValue invalid) {
      throw new DomainException("CUSTOMER_INVALID", "customer." + name + " " + invalid.reason());
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
