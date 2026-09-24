package com.gateway.payments.repository;

import com.gateway.payments.domain.WebhookInboxEntry;
import java.util.Optional;

public interface WebhookInboxRepository {
  WebhookInboxEntry save(WebhookInboxEntry e);

  Optional<WebhookInboxEntry> findById(String id);
}
