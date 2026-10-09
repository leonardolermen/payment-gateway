package com.gateway.billing.customer.persistence;

import com.gateway.billing.customer.Customer;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CustomerRepository {
  /** Requires a transaction. Throws DataIntegrityViolationException on a duplicate document. */
  void insert(Customer customer, byte[] documentCiphertext, String documentHash);

  /** Optimistic: writes only when the stored version is {@code customer.version() - 1}. */
  boolean update(Customer customer);

  Optional<Customer> findActive(MerchantId merchantId, String id);

  Optional<Customer> findActiveByDocumentHash(
      MerchantId merchantId, ProviderEnvironment environment, String documentHash);

  /** Newest first, one environment, deleted ones left out; {@code cursorId} is optional. */
  List<Customer> listActive(
      MerchantId merchantId, ProviderEnvironment environment, String cursorId, int limit);

  /** Like {@link #listActive}, narrowed to names containing {@code query}, case-insensitively. */
  List<Customer> searchActiveByName(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String query,
      String cursorId,
      int limit);

  /** Id to name of the active ones among {@code ids}; a deleted or foreign id is simply absent. */
  Map<String, String> activeNames(MerchantId merchantId, Collection<String> ids);
}
