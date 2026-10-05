package com.gateway.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.ActiveSubscriptionsCheck;
import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanFactory;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.subscription.billing.SubscriptionBilling;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateCardPayment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired SubscriptionService subscriptions;
  @Autowired SubscriptionQueries queries;
  @Autowired CustomerService customers;
  @Autowired PlanService plans;
  @Autowired ActiveSubscriptionsCheck activeSubscriptions;
  @Autowired SubscriptionBilling billing;
  @Autowired OrderService orders;

  Customer customer(String name, String document) {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, name, document, null, null, clock));
  }

  Customer ana() {
    return customer("Ana Silva", "52998224725");
  }

  Plan monthly() {
    return plans.create(
        PlanFactory.fromRequest(
            merchant, "Pro", Money.brl(9900), PlanInterval.MONTH, null, null, clock));
  }

  Subscription subscribe(Customer customer, PaymentMethod method, String cardId, LocalDate day) {
    return subscriptions.create(
        SubscriptionFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, customer, monthly(), method, cardId, day, clock),
        customer);
  }

  Subscription pix(Customer customer) {
    return subscribe(customer, PaymentMethod.PIX, null, BillingCalendar.today(clock.instant()));
  }

  /** A card saved on a payment for this document; the customer created after adopts it. */
  String cardSavedFor(String name, String document) {
    Payment payment =
        paymentService.create(
            new CreateCardPayment(
                merchant,
                ProviderEnvironment.TEST,
                Money.brl(1000),
                "o-1",
                null,
                new CardChoice.NewCard(
                    CardDataFactory.from(
                        "4024007153763171",
                        name.toUpperCase(),
                        "12/2030",
                        "123",
                        null,
                        YearMonth.of(2026, 9)),
                    true),
                null,
                null,
                "LOJA",
                new CardCustomerData(name, document, null),
                null));

    return payment.card().cardId();
  }

  private static String codeOf(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }

    throw new AssertionError("expected a DomainException");
  }

  private int jobsFor(String subscriptionId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.jobs WHERE type = 'BILL_SUBSCRIPTION' AND ref_id = ?",
        Integer.class,
        subscriptionId);
  }

  private List<String> eventsOf(String subscriptionId) {
    return jdbc.queryForList(
        "SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY created_at",
        String.class,
        subscriptionId);
  }

  @Test
  void createStartsActiveQueuesOneBillingJobAndEmits() {
    Subscription created = pix(ana());

    Subscription read = queries.get(merchant, created.id());

    // Started today: billed right now, whatever the hour.
    Instant expected = clock.instant();
    assertThat(read.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(read.nextBillingAt()).isEqualTo(expected);
    assertThat(read.currentPeriod()).isNull();
    assertThat(jobsFor(created.id())).isEqualTo(1);
    assertThat(eventsOf(created.id())).containsExactly("subscription.created");
  }

  @Test
  void aStartTodayBillsNowEvenBeforeTheBillingHour() {
    Instant oneAmSaoPaulo =
        BillingCalendar.billingInstant(BillingCalendar.today(clock.instant()), 1);
    clock.advance(Duration.between(clock.instant(), oneAmSaoPaulo));
    Instant now = clock.instant();

    Subscription created = pix(ana());

    assertThat(queries.get(merchant, created.id()).nextBillingAt()).isEqualTo(now);
  }

  @Test
  void aStartTomorrowBillsAtTheBillingHourEvenWhenCreatedAfterMidnight() {
    Instant oneAmSaoPaulo =
        BillingCalendar.billingInstant(BillingCalendar.today(clock.instant()), 1);
    clock.advance(Duration.between(clock.instant(), oneAmSaoPaulo));
    LocalDate tomorrow = BillingCalendar.today(clock.instant()).plusDays(1);

    Subscription created = subscribe(ana(), PaymentMethod.PIX, null, tomorrow);

    assertThat(queries.get(merchant, created.id()).nextBillingAt())
        .isEqualTo(BillingCalendar.billingInstant(tomorrow, 3));
  }

  @Test
  void aFutureStartBillsAtTheConfiguredSaoPauloHourOfThatDay() {
    LocalDate tomorrow = BillingCalendar.today(clock.instant()).plusDays(1);

    Subscription created = subscribe(ana(), PaymentMethod.PIX, null, tomorrow);

    assertThat(queries.get(merchant, created.id()).nextBillingAt())
        .isEqualTo(BillingCalendar.billingInstant(tomorrow, 3));
  }

  @Test
  void aCardOfAnotherCustomerIsRefused() {
    String bobsCard = cardSavedFor("Bruno Souza", "11144477735");
    customer("Bruno Souza", "11144477735");
    Customer ana = ana();

    assertThat(
            codeOf(
                () ->
                    subscribe(
                        ana, PaymentMethod.CARD, bobsCard, BillingCalendar.today(clock.instant()))))
        .isEqualTo("CARD_NOT_OWNED_BY_CUSTOMER");
  }

  @Test
  void aCardOfTheCustomerIsAccepted() {
    String card = cardSavedFor("Ana Silva", "52998224725");
    Customer ana = ana();

    Subscription created =
        subscribe(ana, PaymentMethod.CARD, card, BillingCalendar.today(clock.instant()));

    assertThat(queries.get(merchant, created.id()).cardId()).isEqualTo(card);
  }

  @Test
  void cancelNowEndsBillingAndEmits() {
    Subscription created = pix(ana());

    Subscription canceled = subscriptions.cancel(merchant, created.id(), false);

    Subscription read = queries.get(merchant, created.id());
    assertThat(canceled.status()).isEqualTo(SubscriptionStatus.CANCELED);
    assertThat(read.status()).isEqualTo(SubscriptionStatus.CANCELED);
    assertThat(read.nextBillingAt()).isNull();
    assertThat(read.canceledAt()).isNotNull();
    assertThat(eventsOf(created.id()))
        .containsExactly("subscription.created", "subscription.canceled");
  }

  /**
   * A subscription started today bills now; one starting on a future day first bills at the billing
   * hour of that day, and until then billOne is not due and creates nothing. Moving the clock to
   * the cycle, as SubscriptionBillingIntegrationTest does, keeps the test independent of the hour
   * it runs at.
   */
  Order billedInvoiceOf(Subscription subscription) {
    if (subscription.nextBillingAt().isAfter(clock.instant())) {
      clock.advance(Duration.between(clock.instant(), subscription.nextBillingAt()));
    }
    billing.billOne(subscription.id(), clock.instant());

    return queries.invoicesOf(merchant, subscription.id()).get(0);
  }

  /** Spec §7: immediate cancel removes the charge at the bank and closes the invoice with it. */
  @Test
  void cancelNowCancelsTheOpenInvoiceAndItsPixAtTheBank() {
    Subscription created = pix(ana());
    Order invoice = billedInvoiceOf(created);
    Payment pix = paymentQueries.listByOrder(merchant, invoice.id()).get(0);
    assertThat(pix.status()).isEqualTo(PaymentStatus.PENDING);

    subscriptions.cancel(merchant, created.id(), false);

    assertThat(paymentQueries.get(merchant, pix.id()).status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(orders.get(merchant, invoice.id()).status()).isEqualTo(OrderStatus.CANCELED);
    assertThat(queries.get(merchant, created.id()).status()).isEqualTo(SubscriptionStatus.CANCELED);
  }

  /**
   * The payer paid while the merchant canceled: the bank refuses to remove the charge, and the
   * subscription is canceled anyway; the invoice stays OPEN for the settlement to mark PAID.
   */
  @Test
  void cancelNowOfAnInvoicePaidAtTheBankStillCancelsTheSubscription() {
    Subscription created = pix(ana());
    Order invoice = billedInvoiceOf(created);
    Payment pix = paymentQueries.listByOrder(merchant, invoice.id()).get(0);
    bank.markPaid(pix.id(), "E2E-1", Money.brl(9900));

    subscriptions.cancel(merchant, created.id(), false);

    assertThat(queries.get(merchant, created.id()).status()).isEqualTo(SubscriptionStatus.CANCELED);
    assertThat(orders.get(merchant, invoice.id()).status()).isEqualTo(OrderStatus.OPEN);
  }

  @Test
  void cancelAtPeriodEndKeepsItActive() {
    Subscription created = pix(ana());

    subscriptions.cancel(merchant, created.id(), true);

    Subscription read = queries.get(merchant, created.id());
    assertThat(read.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(read.cancelAtPeriodEnd()).isTrue();
    assertThat(eventsOf(created.id())).containsExactly("subscription.created");
  }

  @Test
  void cancelOfAFinishedSubscriptionIsAConflict() {
    Subscription created = pix(ana());
    subscriptions.cancel(merchant, created.id(), false);

    assertThat(codeOf(() -> subscriptions.cancel(merchant, created.id(), false)))
        .isEqualTo("SUBSCRIPTION_NOT_ACTIVE");
  }

  @Test
  void changingToBolecodeWithoutAnAddressIsRefused() {
    Customer ana = ana();
    Subscription created = pix(ana);

    assertThat(
            codeOf(
                () ->
                    subscriptions.changeMethod(
                        merchant, created.id(), PaymentMethod.BOLECODE, null, ana)))
        .isEqualTo("CUSTOMER_ADDRESS_REQUIRED");
  }

  @Test
  void changingToTheCustomersCardIsSaved() {
    String card = cardSavedFor("Ana Silva", "52998224725");
    Customer ana = ana();
    Subscription created = pix(ana);

    subscriptions.changeMethod(merchant, created.id(), PaymentMethod.CARD, card, ana);

    Subscription read = queries.get(merchant, created.id());
    assertThat(read.method()).isEqualTo(PaymentMethod.CARD);
    assertThat(read.cardId()).isEqualTo(card);
    assertThat(read.version()).isEqualTo(2L);
  }

  @Test
  void anActiveSubscriptionBlocksDeletingTheCustomer() {
    Customer ana = ana();
    pix(ana);

    assertThat(activeSubscriptions.hasActive(merchant, ana.id())).isTrue();
    assertThat(codeOf(() -> customers.delete(merchant, ana.id())))
        .isEqualTo("CUSTOMER_HAS_ACTIVE_SUBSCRIPTION");
  }

  @Test
  void aCanceledSubscriptionNoLongerBlocks() {
    Customer ana = ana();
    Subscription created = pix(ana);
    subscriptions.cancel(merchant, created.id(), false);

    assertThat(activeSubscriptions.hasActive(merchant, ana.id())).isFalse();
  }

  @Test
  void queriesListByCustomerAndFindNoInvoicesOrDunningYet() {
    Customer ana = ana();
    Subscription created = pix(ana);

    assertThat(queries.listByCustomer(merchant, ana.id()))
        .extracting(Subscription::id)
        .containsExactly(created.id());
    assertThat(queries.invoicesOf(merchant, created.id())).isEmpty();
    assertThat(queries.dunningOf(merchant, created.id())).isEmpty();
  }

  @Test
  void anotherMerchantsSubscriptionIsNotFound() {
    Subscription created = pix(ana());

    assertThatThrownBy(() -> queries.get(MerchantId.next(), created.id()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("NOT_FOUND");
  }
}
