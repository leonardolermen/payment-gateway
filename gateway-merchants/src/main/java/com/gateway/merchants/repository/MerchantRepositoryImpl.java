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
    entity.createdAt = merchant.createdAt();
    entity.updatedAt = merchant.updatedAt();
    return toDomain(jpa.save(entity));
  }

  @Override public Optional<Merchant> findById(MerchantId id) { return jpa.findById(id.value()).map(MerchantRepositoryImpl::toDomain); }
  @Override public List<Merchant> findAll() { return jpa.findAll().stream().map(MerchantRepositoryImpl::toDomain).toList(); }

  private static Merchant toDomain(MerchantEntity e) {
    return new Merchant(new MerchantId(e.id), e.name, MerchantStatus.valueOf(e.status), e.createdAt, e.updatedAt);
  }
}
