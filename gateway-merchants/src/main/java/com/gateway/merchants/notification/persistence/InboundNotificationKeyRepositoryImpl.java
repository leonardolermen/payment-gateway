package com.gateway.merchants.notification.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboundNotificationKeyRepositoryImpl implements InboundNotificationKeyRepository {
  private final InboundNotificationKeyJpaRepository jpa;

  public InboundNotificationKeyRepositoryImpl(InboundNotificationKeyJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  @Transactional
  public void upsert(MerchantId merchantId, String provider, String keyHash) {
    InboundNotificationKeyEntity entity =
        jpa.findByMerchantIdAndProvider(merchantId.value(), provider)
            .orElseGet(
                () -> {
                  InboundNotificationKeyEntity created = new InboundNotificationKeyEntity();
                  created.id = Ulid.next();
                  created.merchantId = merchantId.value();
                  created.provider = provider;
                  return created;
                });
    entity.keyHash = keyHash;
    entity.createdAt = Instant.now();
    jpa.save(entity);
  }

  @Override
  public Optional<String> findHash(MerchantId merchantId, String provider) {
    return jpa.findByMerchantIdAndProvider(merchantId.value(), provider)
        .map(entity -> entity.keyHash);
  }
}
