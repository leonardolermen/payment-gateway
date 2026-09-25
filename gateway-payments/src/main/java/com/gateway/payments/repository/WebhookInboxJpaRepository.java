package com.gateway.payments.repository;

import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookInboxJpaRepository extends JpaRepository<WebhookInboxEntity, String> {}
