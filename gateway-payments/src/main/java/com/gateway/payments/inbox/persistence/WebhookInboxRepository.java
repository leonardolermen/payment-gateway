package com.gateway.payments.inbox.persistence;

import com.gateway.payments.inbox.WebhookInboxEntry;
import java.util.Optional;

public interface WebhookInboxRepository {
  WebhookInboxEntry save(WebhookInboxEntry e);

  Optional<WebhookInboxEntry> findById(String id);
}
