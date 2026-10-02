package com.gateway.billing.customer;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Instant;

public record Customer(
    String id,
    MerchantId merchantId,
    ProviderEnvironment environment,
    PersonName name,
    Document document,
    String email,
    CustomerAddress address,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public Customer withChanges(
      PersonName newName, String newEmail, CustomerAddress newAddress, Instant at) {
    return new Customer(
        id,
        merchantId,
        environment,
        newName,
        document,
        newEmail,
        newAddress,
        version + 1,
        createdAt,
        at,
        deletedAt);
  }

  public Customer deleted(Instant at) {
    return new Customer(
        id,
        merchantId,
        environment,
        name,
        document,
        email,
        address,
        version + 1,
        createdAt,
        at,
        at);
  }

  public boolean hasAddress() {
    return address != null;
  }
}
