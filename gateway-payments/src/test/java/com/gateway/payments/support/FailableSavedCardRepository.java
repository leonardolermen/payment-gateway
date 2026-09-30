package com.gateway.payments.support;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.persistence.SavedCardRepository;
import java.time.Instant;
import java.util.Optional;

/**
 * The real repository with one hook: the next insert throws, as a constraint violation or a lost
 * connection would. Wraps instead of mocking so every other test keeps the real table behind it.
 */
public class FailableSavedCardRepository implements SavedCardRepository {
  private final SavedCardRepository real;
  private volatile RuntimeException failNextInsert;

  public FailableSavedCardRepository(SavedCardRepository real) {
    this.real = real;
  }

  public void failNextInsertWith(RuntimeException e) {
    this.failNextInsert = e;
  }

  @Override
  public void insert(SavedCard card, byte[] tokenCiphertext) {
    RuntimeException fail = failNextInsert;
    if (fail != null) {
      failNextInsert = null;
      throw fail;
    }

    real.insert(card, tokenCiphertext);
  }

  @Override
  public Optional<SavedCard> findActive(MerchantId merchantId, String id) {
    return real.findActive(merchantId, id);
  }

  @Override
  public Optional<byte[]> findActiveToken(MerchantId merchantId, String id) {
    return real.findActiveToken(merchantId, id);
  }

  @Override
  public boolean markDeleted(MerchantId merchantId, String id, Instant at) {
    return real.markDeleted(merchantId, id, at);
  }
}
