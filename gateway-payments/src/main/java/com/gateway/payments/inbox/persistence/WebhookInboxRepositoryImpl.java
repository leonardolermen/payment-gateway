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
    WebhookInboxEntity e = jpa.findById(entry.id()).orElseGet(WebhookInboxEntity::new);
    e.id = entry.id();
    e.provider = entry.provider();
    e.merchantId = entry.merchantId().value();
    e.rawHeaders = entry.rawHeaders();
    e.rawBody = entry.rawBody();
    e.status = entry.status();
    e.error = entry.error();
    e.receivedAt = entry.receivedAt();
    return toDomain(jpa.save(e));
  }

  @Override
  public Optional<WebhookInboxEntry> findById(String id) {
    return jpa.findById(id).map(WebhookInboxRepositoryImpl::toDomain);
  }

  private static WebhookInboxEntry toDomain(WebhookInboxEntity e) {
    return new WebhookInboxEntry(
        e.id,
        e.provider,
        new MerchantId(e.merchantId),
        e.rawHeaders,
        e.rawBody,
        e.status,
        e.error,
        e.receivedAt);
  }
}
