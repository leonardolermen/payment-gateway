package com.gateway.merchants.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.crypto.Encrypted;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import com.gateway.merchants.domain.Provider;
import com.gateway.merchants.domain.ProviderCredential;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ProviderCredentialRepositoryImpl implements ProviderCredentialRepository {
  private final ProviderCredentialJpaRepository jpa;

  public ProviderCredentialRepositoryImpl(ProviderCredentialJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public ProviderCredential save(ProviderCredential credential) {
    ProviderCredentialEntity entity = jpa.findById(credential.id()).orElseGet(ProviderCredentialEntity::new);
    entity.id = credential.id();
    entity.merchantId = credential.merchantId().value();
    entity.provider = credential.provider().name();
    entity.environment = credential.environment().name();
    entity.nonce = credential.payload().nonce();
    entity.ciphertext = credential.payload().ciphertext();
    entity.encryptedDek = credential.payload().encryptedDek();
    entity.dekNonce = credential.payload().dekNonce();
    entity.active = credential.active();
    entity.createdAt = credential.createdAt();
    entity.updatedAt = credential.updatedAt();
    return toDomain(jpa.save(entity));
  }

  @Override
  public Optional<ProviderCredential> find(MerchantId merchantId, Provider provider, ApiKeyEnvironment environment) {
    return jpa.findByMerchantIdAndProviderAndEnvironment(merchantId.value(), provider.name(), environment.name())
        .map(ProviderCredentialRepositoryImpl::toDomain);
  }

  @Override public List<ProviderCredential> findByMerchant(MerchantId merchantId) { return jpa.findByMerchantId(merchantId.value()).stream().map(ProviderCredentialRepositoryImpl::toDomain).toList(); }

  private static ProviderCredential toDomain(ProviderCredentialEntity e) {
    Encrypted payload = new Encrypted(e.nonce, e.ciphertext, e.encryptedDek, e.dekNonce);
    return new ProviderCredential(e.id, new MerchantId(e.merchantId), Provider.valueOf(e.provider), ApiKeyEnvironment.valueOf(e.environment), payload, e.active, e.createdAt, e.updatedAt);
  }
}
