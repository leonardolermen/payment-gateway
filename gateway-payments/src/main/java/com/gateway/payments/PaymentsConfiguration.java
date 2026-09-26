package com.gateway.payments;

import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.idempotency.IdempotencyService;
import com.gateway.payments.idempotency.persistence.IdempotencyRepository;
import com.gateway.payments.idempotency.persistence.IdempotencyRepositoryImpl;
import com.gateway.payments.inbox.WebhookInboxService;
import com.gateway.payments.inbox.persistence.WebhookInboxRepository;
import com.gateway.payments.inbox.persistence.WebhookInboxRepositoryImpl;
import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.jobs.persistence.JobRepositoryImpl;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import com.gateway.payments.outbox.persistence.OutboxRepositoryImpl;
import com.gateway.payments.payment.ExpirationService;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.boleto.BoletoPollingService;
import com.gateway.payments.payment.boleto.persistence.BoletoNumberRepository;
import com.gateway.payments.payment.boleto.persistence.BoletoNumberRepositoryImpl;
import com.gateway.payments.payment.create.BolecodeFromQuery;
import com.gateway.payments.payment.create.BolecodePaymentFlow;
import com.gateway.payments.payment.create.CreateFailures;
import com.gateway.payments.payment.create.PaymentDraftFactory;
import com.gateway.payments.payment.create.PaymentFlow;
import com.gateway.payments.payment.create.PaymentFlows;
import com.gateway.payments.payment.create.PendingAdoption;
import com.gateway.payments.payment.create.PixPaymentFlow;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.payment.persistence.PaymentRepositoryImpl;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.persistence.ProviderRequestRepository;
import com.gateway.payments.provider.persistence.ProviderRequestRepositoryImpl;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.reconciliation.ReconciliationService;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepositoryImpl;
import com.gateway.payments.refund.RefundPollingService;
import com.gateway.payments.refund.RefundService;
import com.gateway.payments.refund.persistence.RefundRepository;
import com.gateway.payments.refund.persistence.RefundRepositoryImpl;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the module exposes, chosen one by one. No component scan: the app imports this class and
 * knows exactly what came in. {@code @EntityScan}/{@code @EnableJpaRepositories} point only at this
 * module's package — each module declares its own and Boot merges them (see the identical note in
 * {@code MerchantsConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.gateway.payments")
@EnableJpaRepositories("com.gateway.payments")
@Import({
  PaymentRepositoryImpl.class,
  RefundRepositoryImpl.class,
  IdempotencyRepositoryImpl.class,
  OutboxRepositoryImpl.class,
  JobRepositoryImpl.class,
  WebhookInboxRepositoryImpl.class,
  ProviderRequestRepositoryImpl.class,
  ReconciliationDivergenceRepositoryImpl.class,
  BoletoNumberRepositoryImpl.class
})
@EnableConfigurationProperties(PaymentsProperties.class)
public class PaymentsConfiguration {
  // PixMethodProvider, CredentialLookup and Clock come from the context, never from here: the app
  // wires
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

  /**
   * ObjectProvider: a context with no BoletoMethodProvider at all (some payments tests) must still
   * start.
   */
  @Bean
  ProviderGateway providerGateway(
      List<PixMethodProvider> providers,
      ObjectProvider<BoletoMethodProvider> boletoProviders,
      CredentialLookup credentials,
      ProviderRequestRepository requests) {
    return new ProviderGateway(
        providers, boletoProviders.orderedStream().toList(), credentials, requests);
  }

  @Bean
  IdempotencyService idempotencyService(
      IdempotencyRepository keys, PaymentsProperties props, Clock clock) {
    return new IdempotencyService(keys, props, clock);
  }

  /** The one adapter from Spring's template to the port the create collaborators take. */
  @Bean
  UnitOfWork unitOfWork(TransactionTemplate paymentsTransactionTemplate) {
    return new TransactionalRunner(paymentsTransactionTemplate);
  }

  @Bean
  Divergences divergences(ReconciliationDivergenceRepository divergences, Clock clock) {
    return new Divergences(divergences, clock);
  }

  @Bean
  PaymentDraftFactory paymentDraftFactory(
      PaymentRepository payments,
      BoletoNumberRepository boletoNumbers,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new PaymentDraftFactory(payments, boletoNumbers, unitOfWork, clock);
  }

  @Bean
  PendingAdoption pendingAdoption(
      PaymentRepository payments,
      JobRepository jobs,
      PaymentEvents events,
      Divergences divergences,
      PaymentsProperties props,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new PendingAdoption(payments, jobs, events, divergences, props, unitOfWork, clock);
  }

  @Bean
  BolecodeFromQuery bolecodeFromQuery(
      PaymentRepository payments, ProviderGateway providers, PendingAdoption adoption) {
    return new BolecodeFromQuery(payments, providers, adoption);
  }

  @Bean
  CreateFailures createFailures(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    return new CreateFailures(payments, events, providers, unitOfWork);
  }

  @Bean
  PixPaymentFlow pixPaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      PendingAdoption adoption,
      CreateFailures failures,
      PaymentsProperties props) {
    return new PixPaymentFlow(providers, drafts, adoption, failures, props);
  }

  @Bean
  BolecodePaymentFlow bolecodePaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      PendingAdoption adoption,
      BolecodeFromQuery fromQuery,
      CreateFailures failures,
      PaymentsProperties props,
      Clock clock) {
    return new BolecodePaymentFlow(providers, drafts, adoption, fromQuery, failures, props, clock);
  }

  /**
   * A list, so a method added without its flow fails the startup instead of a merchant's first
   * request.
   */
  @Bean
  PaymentFlows paymentFlows(List<PaymentFlow> flows) {
    return new PaymentFlows(flows);
  }

  @Bean
  PaymentService paymentService(
      PaymentRepository payments,
      Divergences divergences,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentFlows flows,
      PendingAdoption adoption,
      BolecodeFromQuery bolecodeFromQuery,
      CreateFailures failures,
      PaymentsProperties props,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new PaymentService(
        payments,
        divergences,
        providers,
        events,
        flows,
        adoption,
        bolecodeFromQuery,
        failures,
        props,
        paymentsTransactionTemplate,
        clock);
  }

  @Bean
  RefundService refundService(
      RefundRepository refunds,
      PaymentRepository payments,
      JobRepository jobs,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentService paymentService,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new RefundService(
        refunds,
        payments,
        jobs,
        providers,
        events,
        paymentService,
        paymentsTransactionTemplate,
        clock);
  }

  @Bean
  RefundPollingService refundPollingService(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      RefundService refundService,
      PaymentsProperties props,
      Clock clock) {
    return new RefundPollingService(refunds, payments, providers, refundService, props, clock);
  }

  @Bean
  WebhookInboxService webhookInboxService(
      WebhookInboxRepository inbox,
      JobRepository jobs,
      ProviderGateway providers,
      PaymentService paymentService,
      RefundService refundService,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new WebhookInboxService(
        inbox, jobs, providers, paymentService, refundService, paymentsTransactionTemplate, clock);
  }

  @Bean
  ExpirationService expirationService(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PaymentEvents events,
      PaymentsProperties props,
      TransactionTemplate paymentsTransactionTemplate) {
    return new ExpirationService(
        payments, providers, paymentService, events, props, paymentsTransactionTemplate);
  }

  @Bean
  ReconciliationService reconciliationService(
      PaymentRepository payments,
      ReconciliationDivergenceRepository divergences,
      ProviderGateway providers,
      PaymentService paymentService,
      BoletoPollingService boletoPolling,
      PaymentsProperties props,
      Clock clock) {
    return new ReconciliationService(
        payments, divergences, providers, paymentService, boletoPolling, props, clock);
  }

  @Bean
  BoletoPollingService boletoPollingService(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PaymentsProperties props,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new BoletoPollingService(
        payments, providers, paymentService, props, paymentsTransactionTemplate, clock);
  }

  @Bean
  JobRunner jobRunner(
      JobRepository jobs,
      WebhookInboxService inbox,
      ExpirationService expiration,
      RefundPollingService polling,
      BoletoPollingService boletoPolling,
      RefundService refunds,
      ReconciliationService reconciliation,
      PaymentsProperties props,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new JobRunner(
        jobs,
        inbox,
        expiration,
        polling,
        boletoPolling,
        refunds,
        reconciliation,
        props,
        paymentsTransactionTemplate,
        clock);
  }
}
