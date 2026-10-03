package com.gateway.payments.card.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.card.SavedCard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SavedCardRepository {
  /** Requires a transaction: a card is saved together with the payment that stored it. */
  void insert(SavedCard card, byte[] tokenCiphertext);

  /** Only the merchant's own, not deleted. */
  Optional<SavedCard> findActive(MerchantId merchantId, String id);

  List<SavedCard> findActiveByCustomer(MerchantId merchantId, String customerId);

  /** Requires a transaction; returns how many rows were adopted. */
  int adoptByDocumentHash(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String documentHash,
      String customerId);

  Optional<byte[]> findActiveToken(MerchantId merchantId, String id);

  /** Returns whether a live row was marked. */
  boolean markDeleted(MerchantId merchantId, String id, Instant at);
}
