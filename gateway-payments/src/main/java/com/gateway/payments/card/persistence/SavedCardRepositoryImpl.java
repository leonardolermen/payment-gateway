package com.gateway.payments.card.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.payments.card.SavedCard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SavedCardRepositoryImpl implements SavedCardRepository {
  private final SavedCardJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public SavedCardRepositoryImpl(SavedCardJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist, not save: the id is assigned (a ULID), same reasoning as PaymentRepositoryImpl. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(SavedCard card, byte[] tokenCiphertext) {
    SavedCardEntity entity = new SavedCardEntity();
    entity.id = card.id();
    entity.merchantId = card.merchantId().value();
    entity.provider = card.provider();
    entity.environment = card.environment().name();
    entity.tokenCiphertext = tokenCiphertext;
    entity.brand = card.brand().name();
    entity.last4 = card.last4();
    entity.expiryMonth = (short) card.expiry().getMonthValue();
    entity.expiryYear = (short) card.expiry().getYear();
    entity.holder = card.holder();
    entity.customerDocumentHash = card.customerDocumentHash();
    entity.createdAt = card.createdAt();
    entityManager.persist(entity);
  }

  @Override
  public Optional<SavedCard> findActive(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value())
        .map(SavedCardRepositoryImpl::toDomain);
  }

  @Override
  public Optional<byte[]> findActiveToken(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value())
        .map(entity -> entity.tokenCiphertext);
  }

  @Override
  @Transactional
  public boolean markDeleted(MerchantId merchantId, String id, Instant at) {
    Optional<SavedCardEntity> found =
        jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value());
    found.ifPresent(entity -> entity.deletedAt = at);
    return found.isPresent();
  }

  private static SavedCard toDomain(SavedCardEntity entity) {
    return new SavedCard(
        entity.id,
        new MerchantId(entity.merchantId),
        entity.provider,
        ProviderEnvironment.valueOf(entity.environment),
        CardBrand.valueOf(entity.brand),
        entity.last4,
        YearMonth.of(entity.expiryYear, entity.expiryMonth),
        entity.holder,
        entity.customerDocumentHash,
        entity.createdAt,
        entity.deletedAt);
  }
}
