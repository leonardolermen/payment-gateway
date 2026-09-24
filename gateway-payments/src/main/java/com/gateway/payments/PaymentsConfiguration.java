package com.gateway.payments;

import com.gateway.payments.repository.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What the module exposes, chosen one by one. No component scan: the app imports this class and
 * knows exactly what came in. {@code @EntityScan}/{@code @EnableJpaRepositories} point only at
 * this module's package — each module declares its own and Boot merges them (see the identical
 * note in {@code MerchantsConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.gateway.payments.repository")
@EnableJpaRepositories("com.gateway.payments.repository")
@Import({
  PaymentRepositoryImpl.class,
  RefundRepositoryImpl.class,
  IdempotencyRepositoryImpl.class,
  OutboxRepositoryImpl.class,
  JobRepositoryImpl.class,
  WebhookInboxRepositoryImpl.class,
  ProviderRequestRepositoryImpl.class,
  ReconciliationDivergenceRepositoryImpl.class
})
public class PaymentsConfiguration {}
