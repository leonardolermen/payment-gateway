package com.gateway.payments;

import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.PixProvider;
import com.gateway.payments.repository.*;
import com.gateway.payments.service.*;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsConfiguration {
  // PixProvider, CredentialLookup and Clock come from the context, never from here: the app wires
  // the real bank (gateway-providers) and the merchants' credential store; tests wire in-memory
  // implementations of the same kernel interfaces. A default here would let a missing provider
  // go unnoticed until the first charge.

  @Bean
  TransactionTemplate paymentsTransactionTemplate(PlatformTransactionManager txManager) {
    return new TransactionTemplate(txManager);
  }

  @Bean
  PaymentEvents paymentEvents(OutboxRepository outbox, Clock clock) {
    return new PaymentEvents(outbox, clock);
  }

  @Bean
  ProviderGateway providerGateway(List<PixProvider> providers, CredentialLookup credentials, ProviderRequestRepository requests) {
    return new ProviderGateway(providers, credentials, requests);
  }

  @Bean
  IdempotencyService idempotencyService(IdempotencyRepository keys, PaymentsProperties props, Clock clock) {
    return new IdempotencyService(keys, props, clock);
  }

  @Bean
  PaymentService paymentService(
      PaymentRepository payments, ReconciliationDivergenceRepository divergences, JobRepository jobs, ProviderGateway providers,
      PaymentEvents events, PaymentsProperties props, TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new PaymentService(payments, divergences, jobs, providers, events, props, paymentsTransactionTemplate, clock);
  }

  @Bean
  RefundService refundService(
      RefundRepository refunds, PaymentRepository payments, JobRepository jobs, ProviderGateway providers, PaymentEvents events,
      PaymentService paymentService, TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new RefundService(refunds, payments, jobs, providers, events, paymentService, paymentsTransactionTemplate, clock);
  }

  @Bean
  RefundPollingService refundPollingService(
      RefundRepository refunds, PaymentRepository payments, ProviderGateway providers, RefundService refundService, PaymentsProperties props, Clock clock) {
    return new RefundPollingService(refunds, payments, providers, refundService, props, clock);
  }

  @Bean
  WebhookInboxService webhookInboxService(
      WebhookInboxRepository inbox, JobRepository jobs, ProviderGateway providers, PaymentService paymentService, RefundService refundService,
      TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new WebhookInboxService(inbox, jobs, providers, paymentService, refundService, paymentsTransactionTemplate, clock);
  }

  @Bean
  ExpirationService expirationService(
      PaymentRepository payments, ProviderGateway providers, PaymentService paymentService, PaymentEvents events, PaymentsProperties props,
      TransactionTemplate paymentsTransactionTemplate) {
    return new ExpirationService(payments, providers, paymentService, events, props, paymentsTransactionTemplate);
  }

  @Bean
  ReconciliationService reconciliationService(
      PaymentRepository payments, ReconciliationDivergenceRepository divergences, ProviderGateway providers, PaymentService paymentService,
      PaymentsProperties props, Clock clock) {
    return new ReconciliationService(payments, divergences, providers, paymentService, props, clock);
  }

  @Bean
  JobRunner jobRunner(
      JobRepository jobs, WebhookInboxService inbox, ExpirationService expiration, RefundPollingService polling, RefundService refunds,
      ReconciliationService reconciliation, PaymentsProperties props, TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new JobRunner(jobs, inbox, expiration, polling, refunds, reconciliation, props, paymentsTransactionTemplate, clock);
  }
}
