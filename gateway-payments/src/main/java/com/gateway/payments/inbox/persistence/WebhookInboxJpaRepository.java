package com.gateway.payments.inbox.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookInboxJpaRepository extends JpaRepository<WebhookInboxEntity, String> {}
