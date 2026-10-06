package com.gateway.billing;

import com.gateway.billing.customer.ActiveSubscriptionsCheck;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.customer.persistence.CustomerRepository;
import com.gateway.billing.customer.persistence.CustomerRepositoryImpl;
import com.gateway.billing.order.AttemptSlot;
import com.gateway.billing.order.ExpireOrderJob;
import com.gateway.billing.order.InvoiceSettlementHook;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.order.OrderExpiration;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.order.OrderSettlement;
import com.gateway.billing.order.checkout.CheckoutService;
import com.gateway.billing.order.checkout.CheckoutTokens;
import com.gateway.billing.order.checkout.TokenHasher;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.billing.order.persistence.OrderRepositoryImpl;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.plan.persistence.PlanRepository;
import com.gateway.billing.plan.persistence.PlanRepositoryImpl;
import com.gateway.billing.subscription.ActiveSubscriptions;
import com.gateway.billing.subscription.OpenInvoiceCancellation;
import com.gateway.billing.subscription.SubscriptionQueries;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.billing.BillSubscriptionJob;
import com.gateway.billing.subscription.billing.CycleOpener;
import com.gateway.billing.subscription.billing.Dunning;
import com.gateway.billing.subscription.billing.DunningLedger;
import com.gateway.billing.subscription.billing.DunningRetryJob;
import com.gateway.billing.subscription.billing.DunningSchedule;
import com.gateway.billing.subscription.billing.DunningStarter;
import com.gateway.billing.subscription.billing.InvoiceIssuer;
import com.gateway.billing.subscription.billing.SubscriptionBilling;
import com.gateway.billing.subscription.persistence.DunningAttemptRepository;
import com.gateway.billing.subscription.persistence.DunningAttemptRepositoryImpl;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.billing.subscription.persistence.SubscriptionRepositoryImpl;
import com.gateway.kernel.security.Sealer;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.create.PaymentFlows;
import com.gateway.payments.reconciliation.Divergences;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What billing exposes, chosen one by one; no component scan, same reasoning as {@code
 * PaymentsConfiguration}. Repositories are {@code @Import}ed as they appear.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.gateway.billing")
@EnableJpaRepositories("com.gateway.billing")
@EnableConfigurationProperties(BillingProperties.class)
@Import({
  CustomerRepositoryImpl.class,
  OrderRepositoryImpl.class,
  PlanRepositoryImpl.class,
  SubscriptionRepositoryImpl.class,
  DunningAttemptRepositoryImpl.class
})
public class BillingConfiguration {

  @Bean
  BillingEvents billingEvents(OutboxRepository outbox, Clock clock) {
    return new BillingEvents(outbox, clock);
  }

  @Bean
  CheckoutTokens checkoutTokens(TokenHasher hasher) {
    return new CheckoutTokens(hasher, new SecureRandom());
  }

  @Bean
  ActiveSubscriptionsCheck activeSubscriptions(SubscriptionRepository subscriptions) {
    return new ActiveSubscriptions(subscriptions);
  }

  @Bean
  CustomerService customerService(
      CustomerRepository customers,
      SavedCards savedCards,
      ActiveSubscriptionsCheck activeSubscriptions,
      BillingEvents events,
      Sealer sealer,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new CustomerService(
        customers, savedCards, activeSubscriptions, events, sealer, unitOfWork, clock);
  }

  @Bean
  PlanService planService(PlanRepository plans, UnitOfWork unitOfWork, Clock clock) {
    return new PlanService(plans, unitOfWork, clock);
  }

  @Bean
  CheckoutService checkoutService(
      CheckoutTokens tokens,
      OrderRepository orders,
      OrderAttemptService attempts,
      PaymentQueries payments,
      PaymentCancellation cancellation) {
    return new CheckoutService(tokens, orders, attempts, payments, cancellation);
  }

  @Bean
  OrderAttemptService orderAttemptService(
      PaymentFlows flows,
      PaymentQueries payments,
      CustomerService customers,
      OrderRepository orders,
      AttemptSlot slot) {
    return new OrderAttemptService(flows, payments, customers, orders, slot);
  }

  @Bean
  AttemptSlot attemptSlot(OrderRepository orders, BillingProperties properties, Clock clock) {
    return new AttemptSlot(orders, properties, clock);
  }

  @Bean
  OrderService orderService(
      OrderRepository orders,
      PaymentQueries payments,
      PaymentCancellation cancellation,
      BillingEvents events,
      JobRepository jobs,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new OrderService(orders, payments, cancellation, events, jobs, unitOfWork, clock);
  }

  @Bean
  OrderExpiration orderExpiration(
      OrderRepository orders,
      PaymentQueries payments,
      BillingEvents events,
      UnitOfWork unitOfWork) {
    return new OrderExpiration(orders, payments, events, unitOfWork);
  }

  @Bean
  ExpireOrderJob expireOrderJob(
      OrderExpiration expiration, JobBackoff backoff, BillingProperties properties) {
    return new ExpireOrderJob(expiration, backoff, properties);
  }

  @Bean
  OrderSettlement orderSettlement(
      OrderRepository orders,
      PaymentQueries payments,
      Divergences divergences,
      InvoiceSettlementHook invoices,
      BillingEvents events,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new OrderSettlement(orders, payments, divergences, invoices, events, unitOfWork, clock);
  }

  @Bean
  SubscriptionService subscriptionService(
      SubscriptionRepository subscriptions,
      SavedCards savedCards,
      JobRepository jobs,
      BillingEvents events,
      BillingProperties properties,
      UnitOfWork unitOfWork,
      Clock clock,
      OpenInvoiceCancellation openInvoice) {
    return new SubscriptionService(
        subscriptions, savedCards, jobs, events, properties, unitOfWork, clock, openInvoice);
  }

  @Bean
  OpenInvoiceCancellation openInvoiceCancellation(
      OrderRepository orders, OrderService orderService) {
    return new OpenInvoiceCancellation(orders, orderService);
  }

  @Bean
  SubscriptionQueries subscriptionQueries(
      SubscriptionRepository subscriptions,
      OrderRepository orders,
      DunningAttemptRepository dunning) {
    return new SubscriptionQueries(subscriptions, orders, dunning);
  }

  @Bean
  CycleOpener cycleOpener(
      SubscriptionRepository subscriptions,
      OrderRepository orders,
      PlanService plans,
      JobRepository jobs,
      BillingEvents events,
      BillingProperties properties,
      CheckoutTokens checkoutTokens,
      Clock clock) {
    return new CycleOpener(
        subscriptions,
        orders,
        plans,
        jobs,
        events,
        properties.billingHour(),
        checkoutTokens,
        clock);
  }

  @Bean
  InvoiceIssuer invoiceIssuer(OrderAttemptService attempts, PaymentQueries payments) {
    return new InvoiceIssuer(attempts, payments);
  }

  @Bean
  DunningSchedule dunningSchedule(BillingProperties properties) {
    return new DunningSchedule(properties);
  }

  @Bean
  DunningLedger dunningLedger(
      DunningAttemptRepository attempts,
      JobRepository jobs,
      DunningSchedule schedule,
      Clock clock) {
    return new DunningLedger(attempts, jobs, schedule, clock);
  }

  /**
   * One bean, and it is both ports: SubscriptionBilling takes it as DunningStarter, OrderSettlement
   * as InvoiceSettlementHook. Not two more {@code @Bean} methods returning it typed as each port:
   * by type, each port would then have two candidates (this bean and its alias) and the context
   * would refuse to start.
   */
  @Bean
  Dunning dunning(
      DunningLedger ledger,
      SubscriptionRepository subscriptions,
      OrderRepository orders,
      PaymentQueries payments,
      InvoiceIssuer issuer,
      BillingEvents events,
      UnitOfWork unitOfWork) {
    return new Dunning(ledger, subscriptions, orders, payments, issuer, events, unitOfWork);
  }

  @Bean
  DunningRetryJob dunningRetryJob(
      Dunning dunning, JobBackoff backoff, BillingProperties properties) {
    return new DunningRetryJob(dunning, backoff, properties);
  }

  @Bean
  SubscriptionBilling subscriptionBilling(
      CycleOpener opener,
      InvoiceIssuer issuer,
      PaymentQueries payments,
      DunningStarter dunning,
      BillingEvents events,
      BillingProperties properties,
      UnitOfWork unitOfWork) {
    return new SubscriptionBilling(
        opener, issuer, payments, dunning, events, properties, unitOfWork);
  }

  @Bean
  BillSubscriptionJob billSubscriptionJob(
      SubscriptionBilling billing,
      SubscriptionRepository subscriptions,
      JobBackoff backoff,
      BillingProperties properties) {
    return new BillSubscriptionJob(billing, subscriptions, backoff, properties);
  }
}
