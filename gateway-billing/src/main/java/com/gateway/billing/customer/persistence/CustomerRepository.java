package com.gateway.billing.customer.persistence;

import com.gateway.billing.customer.Customer;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository {
  /** Requires a transaction. Throws DataIntegrityViolationException on a duplicate document. */
  void insert(Customer customer, byte[] documentCiphertext, String documentHash);

  /** Optimistic: writes only when the stored version is {@code customer.version() - 1}. */
  boolean update(Customer customer);

  Optional<Customer> findActive(MerchantId merchantId, String id);

  /** Newest first; {@code cursorIdOrNull} is exclusive. */
  List<Customer> listActive(
      MerchantId merchantId, ProviderEnvironment environment, String cursorIdOrNull, int limit);

  Optional<Customer> findActiveByDocumentHash(
      MerchantId merchantId, ProviderEnvironment environment, String documentHash);
}
