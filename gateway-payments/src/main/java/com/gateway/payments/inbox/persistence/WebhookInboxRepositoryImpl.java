package com.gateway.payments.inbox.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.inbox.WebhookInboxEntry;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookInboxRepositoryImpl implements WebhookInboxRepository {
  private final WebhookInboxJpaRepository jpa;

  public WebhookInboxRepositoryImpl(WebhookInboxJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public WebhookInboxEntry save(WebhookInboxEntry entry) {
    WebhookInboxEntity entity = jpa.findById(entry.id()).orElseGet(WebhookInboxEntity::new);
    entity.id = entry.id();
    entity.provider = entry.provider();
    entity.merchantId = entry.merchantId().value();
    entity.rawHeaders = entry.rawHeaders();
    entity.rawBody = entry.rawBody();
    entity.status = entry.status();
    entity.error = entry.error();
    entity.receivedAt = entry.receivedAt();
    return toDomain(jpa.save(entity));
  }

  @Override
  public Optional<WebhookInboxEntry> findById(String id) {
    return jpa.findById(id).map(WebhookInboxRepositoryImpl::toDomain);
  }

  private static WebhookInboxEntry toDomain(WebhookInboxEntity entity) {
    return new WebhookInboxEntry(
        entity.id,
        entity.provider,
        new MerchantId(entity.merchantId),
        entity.rawHeaders,
        entity.rawBody,
        entity.status,
        entity.error,
        entity.receivedAt);
  }
}
