package com.gateway.payments;

import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.security.Sealer;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.card.persistence.SavedCardRepository;
import com.gateway.payments.card.persistence.SavedCardRepositoryImpl;
import com.gateway.payments.idempotency.IdempotencyService;
import com.gateway.payments.idempotency.persistence.IdempotencyRepository;
import com.gateway.payments.idempotency.persistence.IdempotencyRepositoryImpl;
import com.gateway.payments.inbox.WebhookInboxService;
import com.gateway.payments.inbox.persistence.WebhookInboxRepository;
import com.gateway.payments.inbox.persistence.WebhookInboxRepositoryImpl;
import com.gateway.payments.jobs.ExpirePaymentJob;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobHandlers;
import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.jobs.PollBoletoJob;
import com.gateway.payments.jobs.PollRefundJob;
import com.gateway.payments.jobs.ProcessWebhookJob;
import com.gateway.payments.jobs.ReconcileJob;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.jobs.persistence.JobRepositoryImpl;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import com.gateway.payments.outbox.persistence.OutboxRepositoryImpl;
import com.gateway.payments.payment.BoletoSettlement;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentExpiration;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PixSettlement;
import com.gateway.payments.payment.StuckCreatedSweep;
import com.gateway.payments.payment.boleto.BoletoPollingService;
import com.gateway.payments.payment.boleto.persistence.BoletoNumberRepository;
import com.gateway.payments.payment.boleto.persistence.BoletoNumberRepositoryImpl;
import com.gateway.payments.payment.card.CardAdoption;
import com.gateway.payments.payment.card.CardCapture;
import com.gateway.payments.payment.card.CardVoid;
import com.gateway.payments.payment.create.BolecodeFromQuery;
import com.gateway.payments.payment.create.BolecodePaymentFlow;
import com.gateway.payments.payment.create.CardAuthorizationRecovery;
import com.gateway.payments.payment.create.CardPaymentFlow;
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
import com.gateway.payments.refund.CardRefunds;
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
  BoletoNumberRepositoryImpl.class,
  SavedCardRepositoryImpl.class
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
   * ObjectProvider: a context with no BoletoMethodProvider or CardMethodProvider at all (some
   * payments tests) must still start.
   */
  @Bean
  ProviderGateway providerGateway(
      List<PixMethodProvider> providers,
      ObjectProvider<BoletoMethodProvider> boletoProviders,
      ObjectProvider<CardMethodProvider> cardProviders,
      CredentialLookup credentials,
      ProviderRequestRepository requests) {
    return new ProviderGateway(
        providers,
        boletoProviders.orderedStream().toList(),
        cardProviders.orderedStream().toList(),
        credentials,
        requests);
  }

  @Bean
  IdempotencyService idempotencyService(
      IdempotencyRepository keys, PaymentsProperties properties, Clock clock) {
    return new IdempotencyService(keys, properties, clock);
  }

  /** The one adapter from Spring's template to the port the create collaborators take. */
  @Bean
  UnitOfWork unitOfWork(TransactionTemplate paymentsTransactionTemplate) {
    return new TransactionalRunner(paymentsTransactionTemplate);
  }

  /** Sealer comes from the context: merchants' EnvelopeSealer in the app, TestSealer in tests. */
  @Bean
  SavedCards savedCards(SavedCardRepository cards, Sealer sealer, Clock clock) {
    return new SavedCards(cards, sealer, clock);
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
      PaymentsProperties properties,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new PendingAdoption(payments, jobs, events, divergences, properties, unitOfWork, clock);
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
      PaymentsProperties properties) {
    return new PixPaymentFlow(providers, drafts, adoption, failures, properties);
  }

  @Bean
  BolecodePaymentFlow bolecodePaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      PendingAdoption adoption,
      BolecodeFromQuery fromQuery,
      CreateFailures failures,
      PaymentsProperties properties,
      Clock clock) {
    return new BolecodePaymentFlow(
        providers, drafts, adoption, fromQuery, failures, properties, clock);
  }

  @Bean
  CardAdoption cardAdoption(
      PaymentRepository payments,
      PaymentEvents events,
      SavedCards savedCards,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new CardAdoption(payments, events, savedCards, unitOfWork, clock);
  }

  @Bean
  CardAuthorizationRecovery cardAuthorizationRecovery(
      ProviderGateway providers, CardAdoption adoption, CreateFailures failures) {
    return new CardAuthorizationRecovery(providers, adoption, failures);
  }

  @Bean
  CardPaymentFlow cardPaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      CardAdoption adoption,
      CardAuthorizationRecovery recovery,
      SavedCards savedCards,
      CreateFailures failures) {
    return new CardPaymentFlow(providers, drafts, adoption, recovery, savedCards, failures);
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
      Divergences divergences,
      PaymentFlows flows,
      PendingAdoption adoption,
      BolecodeFromQuery bolecodeFromQuery,
      CreateFailures failures) {
    return new PaymentService(divergences, flows, adoption, bolecodeFromQuery, failures);
  }

  @Bean
  PaymentQueries paymentQueries(PaymentRepository payments) {
    return new PaymentQueries(payments);
  }

  @Bean
  PixSettlement pixSettlement(
      PaymentRepository payments,
      Divergences divergences,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    return new PixSettlement(payments, divergences, events, providers, unitOfWork);
  }

  @Bean
  BoletoSettlement boletoSettlement(
      PaymentRepository payments,
      Divergences divergences,
      PaymentEvents events,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new BoletoSettlement(payments, divergences, events, unitOfWork, clock);
  }

  @Bean
  CardCapture cardCapture(
      PaymentQueries queries,
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    return new CardCapture(queries, payments, events, providers, unitOfWork);
  }

  @Bean
  CardVoid cardVoid(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    return new CardVoid(payments, events, providers, unitOfWork);
  }

  @Bean
  CardRefunds cardRefunds(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentEvents events,
      Divergences divergences,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new CardRefunds(
        refunds, payments, providers, events, divergences, paymentsTransactionTemplate, clock);
  }

  @Bean
  PaymentCancellation paymentCancellation(
      PaymentQueries queries,
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork,
      BoletoSettlement boletoSettlement,
      CardVoid cardVoid) {
    return new PaymentCancellation(
        queries, payments, events, providers, unitOfWork, boletoSettlement, cardVoid);
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
      Clock clock,
      CardRefunds cardRefunds) {
    return new RefundService(
        refunds,
        payments,
        jobs,
        providers,
        events,
        paymentService,
        paymentsTransactionTemplate,
        clock,
        cardRefunds);
  }

  @Bean
  RefundPollingService refundPollingService(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      RefundService refundService,
      PaymentsProperties properties,
      Clock clock) {
    return new RefundPollingService(refunds, payments, providers, refundService, properties, clock);
  }

  @Bean
  WebhookInboxService webhookInboxService(
      WebhookInboxRepository inbox,
      JobRepository jobs,
      ProviderGateway providers,
      PixSettlement pixSettlement,
      RefundService refundService,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new WebhookInboxService(
        inbox, jobs, providers, pixSettlement, refundService, paymentsTransactionTemplate, clock);
  }

  @Bean
  PaymentExpiration paymentExpiration(
      PaymentRepository payments,
      ProviderGateway providers,
      PixSettlement pixSettlement,
      BoletoSettlement boletoSettlement,
      PaymentEvents events,
      PaymentsProperties properties,
      UnitOfWork unitOfWork) {
    return new PaymentExpiration(
        payments, providers, pixSettlement, boletoSettlement, events, properties, unitOfWork);
  }

  @Bean
  StuckCreatedSweep stuckCreatedSweep(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PixSettlement pixSettlement,
      BoletoSettlement boletoSettlement,
      PaymentsProperties properties,
      CardAuthorizationRecovery cardRecovery) {
    return new StuckCreatedSweep(
        payments,
        providers,
        paymentService,
        pixSettlement,
        boletoSettlement,
        properties,
        cardRecovery);
  }

  @Bean
  ReconciliationService reconciliationService(
      PaymentRepository payments,
      ReconciliationDivergenceRepository divergences,
      ProviderGateway providers,
      PaymentService paymentService,
      PixSettlement pixSettlement,
      BoletoPollingService boletoPolling,
      PaymentsProperties properties,
      Clock clock) {
    return new ReconciliationService(
        payments,
        divergences,
        providers,
        paymentService,
        pixSettlement,
        boletoPolling,
        properties,
        clock);
  }

  @Bean
  BoletoPollingService boletoPollingService(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PixSettlement pixSettlement,
      BoletoSettlement boletoSettlement,
      PaymentsProperties properties,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new BoletoPollingService(
        payments,
        providers,
        paymentService,
        pixSettlement,
        boletoSettlement,
        properties,
        paymentsTransactionTemplate,
        clock);
  }

  @Bean
  JobBackoff jobBackoff(PaymentsProperties properties) {
    return new JobBackoff(properties);
  }

  @Bean
  ProcessWebhookJob processWebhookJob(WebhookInboxService inbox, JobBackoff backoff) {
    return new ProcessWebhookJob(inbox, backoff);
  }

  @Bean
  ExpirePaymentJob expirePaymentJob(PaymentExpiration expiration, JobBackoff backoff) {
    return new ExpirePaymentJob(expiration, backoff);
  }

  @Bean
  PollRefundJob pollRefundJob(
      RefundPollingService polling, RefundService refunds, PaymentsProperties properties) {
    return new PollRefundJob(polling, refunds, properties);
  }

  @Bean
  ReconcileJob reconcileJob(
      StuckCreatedSweep sweep, ReconciliationService reconciliation, JobBackoff backoff) {
    return new ReconcileJob(sweep, reconciliation, backoff);
  }

  @Bean
  PollBoletoJob pollBoletoJob(BoletoPollingService boletoPolling, PaymentsProperties properties) {
    return new PollBoletoJob(boletoPolling, properties);
  }

  /** A list, so a job type added without its handler fails the startup instead of its first run. */
  @Bean
  JobHandlers jobHandlers(List<JobHandler> handlers) {
    return new JobHandlers(handlers);
  }

  @Bean
  JobRunner jobRunner(
      JobRepository jobs,
      JobHandlers handlers,
      PaymentsProperties properties,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new JobRunner(jobs, handlers, properties, paymentsTransactionTemplate, clock);
  }
}
