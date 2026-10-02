package com.gateway.billing;

import com.gateway.billing.customer.ActiveSubscriptionsCheck;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.customer.persistence.CustomerRepository;
import com.gateway.billing.customer.persistence.CustomerRepositoryImpl;
import com.gateway.billing.order.ExpireOrderJob;
import com.gateway.billing.order.InvoiceSettlementHook;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.order.OrderExpiration;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.order.OrderSettlement;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.billing.order.persistence.OrderRepositoryImpl;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.plan.persistence.PlanRepository;
import com.gateway.billing.plan.persistence.PlanRepositoryImpl;
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
import java.time.Clock;
import java.time.Instant;
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
@Import({CustomerRepositoryImpl.class, OrderRepositoryImpl.class, PlanRepositoryImpl.class})
public class BillingConfiguration {

  @Bean
  BillingEvents billingEvents(OutboxRepository outbox, Clock clock) {
    return new BillingEvents(outbox, clock);
  }

  /** Task 8 replaces this with the real check against subscriptions; until then none can exist. */
  @Bean
  ActiveSubscriptionsCheck noActiveSubscriptionsYet() {
    return (merchantId, customerId) -> false;
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
  OrderAttemptService orderAttemptService(
      PaymentFlows flows,
      PaymentQueries payments,
      CustomerService customers,
      OrderRepository orders) {
    return new OrderAttemptService(flows, payments, customers, orders);
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

  /** Task 10 replaces this with the subscription's reaction; until then no invoice exists. */
  @Bean
  InvoiceSettlementHook noInvoiceHookYet() {
    return new InvoiceSettlementHook() {
      @Override
      public void invoicePaid(Order order, Instant at) {}

      @Override
      public void invoiceAttemptFailed(
          Order order, String paymentId, String eventType, Instant at) {}
    };
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
}
