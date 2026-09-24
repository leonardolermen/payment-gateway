package com.gateway.merchants.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.Merchant;
import com.gateway.merchants.domain.MerchantStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantRepositoryImpl implements MerchantRepository {
  private final MerchantJpaRepository jpa;

  public MerchantRepositoryImpl(MerchantJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Merchant save(Merchant merchant) {
    MerchantEntity entity = jpa.findById(merchant.id().value()).orElseGet(MerchantEntity::new);
    entity.id = merchant.id().value();
    entity.name = merchant.name();
    entity.status = merchant.status().name();
    entity.inboundWebhookToken = merchant.inboundWebhookToken();
    entity.createdAt = merchant.createdAt();
    entity.updatedAt = merchant.updatedAt();
    return toDomain(jpa.save(entity));
  }

  @Override public Optional<Merchant> findById(MerchantId id) { return jpa.findById(id.value()).map(MerchantRepositoryImpl::toDomain); }
  @Override public List<Merchant> findAll() { return jpa.findAll().stream().map(MerchantRepositoryImpl::toDomain).toList(); }

  @Override public Optional<Merchant> findByInboundWebhookToken(String token) { return jpa.findByInboundWebhookToken(token).map(MerchantRepositoryImpl::toDomain); }

  private static Merchant toDomain(MerchantEntity e) {
    return Merchant.rehydrate(new MerchantId(e.id), e.name, MerchantStatus.valueOf(e.status), e.inboundWebhookToken, e.createdAt, e.updatedAt);
  }
}
