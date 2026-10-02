package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.BillingProperties;
import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanFactory;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionFactory;
import com.gateway.billing.subscription.SubscriptionQueries;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateCardPayment;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionBillingIntegrationTest extends BillingIntegrationTestBase {
  @Autowired SubscriptionBilling billing;
  @Autowired SubscriptionService service;
  @Autowired SubscriptionQueries queries;
  @Autowired CustomerService customers;
  @Autowired PlanService plans;
  @Autowired BillSubscriptionJob job;

  @Autowired CycleOpener opener;
  @Autowired InvoiceIssuer issuer;
  @Autowired SubscriptionRepository subscriptions;
  @Autowired BillingEvents events;
  @Autowired UnitOfWork unitOfWork;

  @Test
  void aCardCycleCreatesTheInvoiceChargesItAndSchedulesTheNext() {
    Subscription subscription = cardSubscription();

    boolean done = billing.billOne(subscription.id(), clock.instant());

    assertThat(done).isTrue();
    Order invoice = invoicesOf(subscription).get(0);
    assertThat(invoice.invoiceNumber()).isEqualTo(1);
    assertThat(invoice.amount()).isEqualTo(Money.brl(9900));
    assertThat(paymentQueries.listByOrder(merchant, invoice.id()))
        .singleElement()
        .extracting(Payment::status)
        .isEqualTo(PaymentStatus.COMPLETED);
    Subscription after = queries.get(merchant, subscription.id());
    assertThat(after.lastInvoiceNumber()).isEqualTo(1);
    assertThat(after.nextBillingAt())
        .isEqualTo(BillingCalendar.billingInstant(after.currentPeriod().end(), 3));
    // One row per subscription: the job table is unique on (type, ref_id).
    assertThat(jobsFor("BILL_SUBSCRIPTION", subscription.id())).isEqualTo(1);
    assertThat(outboxTypes(invoice.id())).contains("order.created", "invoice.created");
  }

  @Test
  void aPixCycleIssuesTheChargeAndPublishesTheCopyPaste() {
    Subscription subscription = pixSubscription();

    billing.billOne(subscription.id(), clock.instant());

    String invoiceCreated = outboxPayload(invoicesOf(subscription).get(0).id(), "invoice.created");
    assertThat(invoiceCreated).contains("\"method\":\"PIX\"").contains("copia_e_cola");
  }

  @Test
  void aDeclinedCardLeavesTheInvoiceOpenAndTheSubscriptionPastDue() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);

    billing.billOne(subscription.id(), clock.instant());

    assertThat(invoicesOf(subscription).get(0).status()).isEqualTo(OrderStatus.OPEN);
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(outboxTypes(subscription.id())).contains("subscription.past_due");
  }

  @Test
  void aRerunAfterTheInvoiceExistsDoesNotChargeTwice() {
    Subscription subscription = cardSubscription();
    billing.billOne(subscription.id(), clock.instant());
    // Simulate the crash between transaction 1 and the attempt: rewind next_billing_at.
    jdbc.update(
        "UPDATE billing.subscriptions SET next_billing_at = ? WHERE id = ?",
        java.sql.Timestamp.from(clock.instant()),
        subscription.id());

    billing.billOne(subscription.id(), clock.instant());

    assertThat(invoicesOf(subscription)).hasSize(1);
    assertThat(paymentQueries.listByOrder(merchant, invoicesOf(subscription).get(0).id()))
        .hasSize(1);
  }

  @Test
  void aCycleWhoseBankCallFailedIsChargedOnTheRetry() {
    Subscription subscription = cardSubscription();
    cards.failNextAuthorizeWith(
        new ProviderException(ProviderException.Code.UNAVAILABLE, 503, "CIELO", "down"));
    assertThatThrownBy(() -> billing.billOne(subscription.id(), clock.instant()))
        .isInstanceOf(RuntimeException.class);

    billing.billOne(subscription.id(), clock.instant());

    assertThat(invoicesOf(subscription)).hasSize(1);
    // The unreachable bank left its FAILED row; the retry is the one charge.
    assertThat(paymentQueries.listByOrder(merchant, invoicesOf(subscription).get(0).id()))
        .extracting(Payment::status)
        .containsOnlyOnce(PaymentStatus.COMPLETED)
        .doesNotContain(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.AUTHORIZED);
  }

  @Test
  void aDeclineWhoseBookingWasLostIsBookedOnTheRetryWithoutChargingAgain() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    String invoiceId = invoicesOf(subscription).get(0).id();
    // Undo exactly what transaction 2 wrote: the status and its two events.
    jdbc.update(
        "UPDATE billing.subscriptions SET status = 'ACTIVE' WHERE id = ?", subscription.id());
    jdbc.update(
        "DELETE FROM payments.outbox WHERE event_type IN ('invoice.created',"
            + " 'subscription.past_due') AND aggregate_id IN (?, ?)",
        invoiceId,
        subscription.id());

    billing.billOne(subscription.id(), clock.instant());
    billing.billOne(subscription.id(), clock.instant());

    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(paymentQueries.listByOrder(merchant, invoiceId)).hasSize(1);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.past_due");
    assertThat(outboxTypes(invoiceId)).containsOnlyOnce("invoice.created");
  }

  @Test
  void cancelAtPeriodEndEndsInsteadOfBilling() {
    Subscription subscription = pixSubscription();
    billing.billOne(subscription.id(), clock.instant());
    service.cancel(merchant, subscription.id(), true);
    clock.advance(Duration.ofDays(31));

    billing.billOne(subscription.id(), clock.instant());

    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.ENDED);
    assertThat(invoicesOf(subscription)).hasSize(1);
  }

  @Test
  void withRecurringDisabledACardCycleIsNotAttempted() {
    Subscription subscription = cardSubscription();
    List<String> firstFailures = new ArrayList<>();
    DunningStarter recording = (failed, invoice, paymentId, now) -> firstFailures.add(invoice.id());
    SubscriptionBilling withoutRecurring =
        new SubscriptionBilling(
            opener,
            issuer,
            paymentQueries,
            subscriptions,
            recording,
            events,
            new BillingProperties(null, 0, false, null),
            unitOfWork);

    withoutRecurring.billOne(subscription.id(), clock.instant());

    Order invoice = invoicesOf(subscription).get(0);
    assertThat(paymentQueries.listByOrder(merchant, invoice.id())).isEmpty();
    assertThat(outboxPayload(invoice.id(), "invoice.created"))
        .contains("\"charged\":false")
        .contains("\"reason\":\"CARD_RECURRING_UNSUPPORTED\"");
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(outboxTypes(subscription.id())).contains("subscription.past_due");
    assertThat(firstFailures).containsExactly(invoice.id());
  }

  @Test
  void theJobComesBackPendingAtTheNextBillingInstant() {
    Subscription subscription = pixSubscription();
    Job claimed = Job.billSubscription(subscription.id(), clock.instant(), clock);

    boolean done = job.run(subscription.id(), clock.instant());
    Job saved = job.finish(claimed, claimed.done(), clock.instant());

    assertThat(done).isTrue();
    assertThat(saved.status()).isEqualTo("PENDING");
    assertThat(saved.nextRunAt())
        .isEqualTo(queries.get(merchant, subscription.id()).nextBillingAt());
  }

  private Customer ana() {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null, null, clock));
  }

  private Plan monthly() {
    return plans.create(
        PlanFactory.fromRequest(
            merchant, "Pro", Money.brl(9900), PlanInterval.MONTH, null, null, clock));
  }

  private Subscription pixSubscription() {
    return subscribe(ana(), PaymentMethod.PIX, null);
  }

  /** The card is saved on a payment for Ana's document; the customer created after adopts it. */
  private Subscription cardSubscription() {
    String cardId = cardSavedFor("Ana Silva", "52998224725");

    return subscribe(ana(), PaymentMethod.CARD, cardId);
  }

  /**
   * A start today is billed at 03:00 São Paulo or now, whichever is later; before 03:00 the first
   * cycle is not due yet, so the clock moves to it and the tests do not depend on the hour they
   * run.
   */
  private Subscription subscribe(Customer customer, PaymentMethod method, String cardId) {
    LocalDate today = BillingCalendar.today(clock.instant());
    Subscription created =
        service.create(
            SubscriptionFactory.fromRequest(
                merchant,
                ProviderEnvironment.TEST,
                customer,
                monthly(),
                method,
                cardId,
                today,
                clock),
            customer);

    if (created.nextBillingAt().isAfter(clock.instant())) {
      clock.advance(Duration.between(clock.instant(), created.nextBillingAt()));
    }

    return created;
  }

  private String cardSavedFor(String name, String document) {
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

  private List<Order> invoicesOf(Subscription subscription) {
    return queries.invoicesOf(merchant, subscription.id());
  }

  private int jobsFor(String type, String refId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.jobs WHERE type = ? AND ref_id = ?",
        Integer.class,
        type,
        refId);
  }

  private List<String> outboxTypes(String aggregateId) {
    return jdbc.queryForList(
        "SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY created_at",
        String.class,
        aggregateId);
  }

  private String outboxPayload(String aggregateId, String type) {
    return jdbc.queryForObject(
        "SELECT payload FROM payments.outbox WHERE aggregate_id = ? AND event_type = ?",
        String.class,
        aggregateId,
        type);
  }
}
