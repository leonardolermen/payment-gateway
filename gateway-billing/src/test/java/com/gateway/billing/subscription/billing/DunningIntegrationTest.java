package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderSettlement;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanFactory;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.DunningAttempt;
import com.gateway.billing.subscription.DunningOutcome;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionFactory;
import com.gateway.billing.subscription.SubscriptionQueries;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.billing.subscription.persistence.DunningAttemptRepository;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.outbox.OutboxMessage;
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
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DunningIntegrationTest extends BillingIntegrationTestBase {
  @Autowired SubscriptionBilling billing;
  @Autowired Dunning dunning;
  @Autowired OrderSettlement settlement;
  @Autowired SubscriptionService service;
  @Autowired SubscriptionQueries queries;
  @Autowired CustomerService customers;
  @Autowired PlanService plans;
  @Autowired DunningAttemptRepository attempts;
  @Autowired UnitOfWork unitOfWork;

  @Test
  void aDeclinedCycleSchedulesTheFirstRetryForTheNextDayAtTheBillingHour() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    Instant failedAt = clock.instant();

    billing.billOne(subscription.id(), failedAt);

    DunningAttempt first = attemptsOf(subscription).get(0);
    assertThat(attemptsOf(subscription)).hasSize(1);
    assertThat(first.attempt()).isEqualTo(1);
    assertThat(first.outcome()).isNull();
    assertThat(first.scheduledAt()).isEqualTo(retryDay(failedAt, 1));
    assertThat(jobsFor(first.id())).isEqualTo(1);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.past_due");
  }

  @Test
  void declinedTwiceThenApprovedRecoversTheSubscription() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    DunningAttempt first = attemptsOf(subscription).get(0);

    clock.advance(Duration.ofDays(1));
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    Instant secondFailure = clock.instant();
    assertThat(dunning.retryOne(first.id(), secondFailure)).isTrue();

    DunningAttempt second = attemptsOf(subscription).get(1);
    assertThat(attemptsOf(subscription).get(0).outcome()).isEqualTo(DunningOutcome.DECLINED);
    assertThat(attemptsOf(subscription).get(0).paymentId()).isNotNull();
    assertThat(second.attempt()).isEqualTo(2);
    assertThat(second.scheduledAt()).isEqualTo(retryDay(secondFailure, 3));
    assertThat(jobsFor(second.id())).isEqualTo(1);

    clock.advance(Duration.ofDays(3));
    assertThat(dunning.retryOne(second.id(), clock.instant())).isTrue();

    DunningAttempt paid = attemptsOf(subscription).get(1);
    assertThat(paid.outcome()).isEqualTo(DunningOutcome.PAID);
    Payment payment = paymentQueries.get(merchant, paid.paymentId());
    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(attemptsOf(subscription)).hasSize(2);

    Order invoice = invoiceOf(subscription);
    settlement.on(event("payment.completed", invoice, payment));

    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(invoiceOf(subscription).status()).isEqualTo(OrderStatus.PAID);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.recovered");
  }

  @Test
  void threeDeclinedRetriesExhaustTheInvoiceWithoutCancelling() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());

    for (int retry = 0; retry < 3; retry++) {
      DunningAttempt pending = attempts.findPendingByOrder(invoiceOf(subscription).id()).get();
      clock.advance(Duration.ofDays(1));
      cards.nextAuthorizeStatus(CardStatus.DENIED);
      dunning.retryOne(pending.id(), clock.instant());
    }

    assertThat(attemptsOf(subscription))
        .extracting(DunningAttempt::outcome)
        .containsExactly(DunningOutcome.DECLINED, DunningOutcome.DECLINED, DunningOutcome.DECLINED);
    assertThat(attempts.findPendingByOrder(invoiceOf(subscription).id())).isEmpty();
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.dunning_exhausted");
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(invoiceOf(subscription).status()).isEqualTo(OrderStatus.OPEN);
  }

  @Test
  void anExpiredPixStartsDunningAndARetryWaitsForALiveAttempt() {
    Subscription subscription = subscribe(ana(), PaymentMethod.PIX, null);
    billing.billOne(subscription.id(), clock.instant());
    Order invoice = invoiceOf(subscription);
    Payment pix = paymentQueries.listByOrder(merchant, invoice.id()).get(0);

    settlement.on(event("payment.expired", invoice, pix));

    DunningAttempt first = attemptsOf(subscription).get(0);
    assertThat(first.attempt()).isEqualTo(1);
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.past_due");
    // The bank has not expired the Pix yet: it is still the invoice's live attempt.
    assertThat(dunning.retryOne(first.id(), clock.instant())).isFalse();
    assertThat(attemptsOf(subscription).get(0).outcome()).isNull();
  }

  @Test
  void aBoletoRefusedForAMissingAddressSkipsThisAttemptAndSchedulesTheNext() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    DunningAttempt first = attemptsOf(subscription).get(0);
    // changeMethod refuses BOLECODE without an address; the customer lost it after the switch.
    jdbc.update(
        "UPDATE billing.subscriptions SET method = 'BOLECODE', card_id = NULL WHERE id = ?",
        subscription.id());

    clock.advance(Duration.ofDays(1));
    boolean done = dunning.retryOne(first.id(), clock.instant());

    assertThat(done).isTrue();
    DunningAttempt skipped = attemptsOf(subscription).get(0);
    assertThat(skipped.outcome()).isEqualTo(DunningOutcome.SKIPPED);
    assertThat(skipped.paymentId()).isNull();
    assertThat(attemptsOf(subscription)).hasSize(2);
    assertThat(attemptsOf(subscription).get(1).outcome()).isNull();
    assertThat(outboxTypes(subscription.id())).doesNotContain("subscription.dunning_exhausted");
  }

  @Test
  void aRetryOfAnExpiredPixIssuesAFreshOneAndSchedulesTheNextRetry() {
    Subscription subscription = subscribe(ana(), PaymentMethod.PIX, null);
    billing.billOne(subscription.id(), clock.instant());
    Order invoice = invoiceOf(subscription);
    expire(invoice, latestPayment(invoice));
    DunningAttempt first = attemptsOf(subscription).get(0);

    clock.advance(Duration.ofDays(1));
    assertThat(dunning.retryOne(first.id(), clock.instant())).isTrue();

    DunningAttempt issued = attemptsOf(subscription).get(0);
    assertThat(issued.outcome()).isEqualTo(DunningOutcome.ISSUED);
    Payment fresh = paymentQueries.get(merchant, issued.paymentId());
    assertThat(fresh.status().isActive()).isTrue();
    assertThat(paymentQueries.activeAttempt(invoice.id())).map(Payment::id).contains(fresh.id());
    assertThat(outboxTypes(invoice.id())).containsOnlyOnce("invoice.updated");
    assertThat(outboxPayload(invoice.id(), "invoice.updated"))
        .contains("copia_e_cola")
        .contains("\"attempt\":1");
    assertThat(attemptsOf(subscription)).hasSize(2);
    assertThat(attemptsOf(subscription).get(1).outcome()).isNull();
  }

  @Test
  void theExpiryOfTheLastReissuedPixExhaustsTheInvoiceOnce() {
    Subscription subscription = subscribe(ana(), PaymentMethod.PIX, null);
    billing.billOne(subscription.id(), clock.instant());
    Order invoice = invoiceOf(subscription);
    expire(invoice, latestPayment(invoice));

    for (int retry = 0; retry < 3; retry++) {
      DunningAttempt pending = attempts.findPendingByOrder(invoice.id()).get();
      clock.advance(Duration.ofDays(1));
      dunning.retryOne(pending.id(), clock.instant());
      // Before the last retry, this expiry finds the next one pending and does nothing.
      expire(invoice, latestPayment(invoice));
    }

    assertThat(attemptsOf(subscription))
        .extracting(DunningAttempt::outcome)
        .containsExactly(DunningOutcome.ISSUED, DunningOutcome.ISSUED, DunningOutcome.ISSUED);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.dunning_exhausted");
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
  }

  @Test
  void payingOneOfTwoChasedInvoicesRecoversOnlyWhenTheOtherIsPaidToo() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Instant secondCycle = queries.get(merchant, subscription.id()).nextBillingAt();
    clock.advance(Duration.between(clock.instant(), secondCycle));
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Order firstInvoice = invoiceNumbered(subscription, 1);
    Order secondInvoice = invoiceNumbered(subscription, 2);
    assertThat(attempts.findPendingByOrder(secondInvoice.id())).isPresent();

    payByRetry(firstInvoice);

    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(outboxTypes(subscription.id())).doesNotContain("subscription.recovered");

    payByRetry(secondInvoice);

    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.recovered");
  }

  /**
   * An expired invoice is no longer chased: its SKIPPED retry must not leave the subscription
   * PAST_DUE by itself once the other invoice is paid.
   */
  @Test
  void anExpiredChasedInvoiceDoesNotKeepTheSubscriptionPastDue() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Instant secondCycle = queries.get(merchant, subscription.id()).nextBillingAt();
    clock.advance(Duration.between(clock.instant(), secondCycle));
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Order firstInvoice = invoiceNumbered(subscription, 1);
    Order secondInvoice = invoiceNumbered(subscription, 2);

    expireOrder(firstInvoice);
    DunningAttempt pending = attempts.findPendingByOrder(firstInvoice.id()).get();
    dunning.retryOne(pending.id(), clock.instant());

    assertThat(attempts.findById(pending.id()).get().outcome()).isEqualTo(DunningOutcome.SKIPPED);
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.PAST_DUE);

    payByRetry(secondInvoice);

    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.recovered");
  }

  @Test
  void theOnlyChasedInvoiceExpiringRecoversTheSubscription() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Order invoice = invoiceOf(subscription);

    expireOrder(invoice);
    DunningAttempt pending = attempts.findPendingByOrder(invoice.id()).get();
    dunning.retryOne(pending.id(), clock.instant());

    assertThat(attempts.findById(pending.id()).get().outcome()).isEqualTo(DunningOutcome.SKIPPED);
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(outboxTypes(subscription.id())).containsOnlyOnce("subscription.recovered");
  }

  @Test
  void aPaidInvoiceSkipsItsPendingRetry() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Order invoice = invoiceOf(subscription);

    unitOfWork.run(() -> dunning.invoicePaid(invoice, clock.instant()));

    assertThat(attemptsOf(subscription))
        .singleElement()
        .extracting(DunningAttempt::outcome)
        .isEqualTo(DunningOutcome.SKIPPED);
    assertThat(queries.get(merchant, subscription.id()).status())
        .isEqualTo(SubscriptionStatus.ACTIVE);
  }

  @Test
  void aSecondRunOfAClosedAttemptDoesNothing() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    billing.billOne(subscription.id(), clock.instant());
    Order invoice = invoiceOf(subscription);
    DunningAttempt first = attemptsOf(subscription).get(0);
    clock.advance(Duration.ofDays(1));
    cards.nextAuthorizeStatus(CardStatus.DENIED);
    dunning.retryOne(first.id(), clock.instant());
    int paymentsBefore = paymentQueries.listByOrder(merchant, invoice.id()).size();
    int eventsBefore = outboxCount(subscription, invoice);

    boolean done = dunning.retryOne(first.id(), clock.instant());

    assertThat(done).isTrue();
    assertThat(paymentQueries.listByOrder(merchant, invoice.id())).hasSize(paymentsBefore);
    assertThat(outboxCount(subscription, invoice)).isEqualTo(eventsBefore);
    assertThat(attemptsOf(subscription)).hasSize(2);
  }

  /** Approves the pending retry of the invoice and relays its payment.completed. */
  private void payByRetry(Order invoice) {
    DunningAttempt pending = attempts.findPendingByOrder(invoice.id()).get();
    dunning.retryOne(pending.id(), clock.instant());
    String paymentId = attempts.findById(pending.id()).get().paymentId();
    settlement.on(event("payment.completed", invoice, paymentQueries.get(merchant, paymentId)));
  }

  /** The invoice's period ran out unpaid, as OrderExpiration leaves it. */
  private void expireOrder(Order invoice) {
    jdbc.update("UPDATE billing.orders SET status = 'EXPIRED' WHERE id = ?", invoice.id());
  }

  /** The bank expired the payment, and its payment.expired reached the settlement. */
  private void expire(Order invoice, Payment payment) {
    jdbc.update("UPDATE payments.payments SET status = 'EXPIRED' WHERE id = ?", payment.id());
    settlement.on(event("payment.expired", invoice, payment));
  }

  private Payment latestPayment(Order invoice) {
    return paymentQueries.listByOrder(merchant, invoice.id()).stream()
        .max(Comparator.comparing(Payment::id))
        .orElseThrow();
  }

  private Order invoiceNumbered(Subscription subscription, int number) {
    return queries.invoicesOf(merchant, subscription.id()).stream()
        .filter(invoice -> invoice.invoiceNumber() == number)
        .findFirst()
        .orElseThrow();
  }

  private int outboxCount(Subscription subscription, Order invoice) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.outbox WHERE aggregate_id IN (?, ?)",
        Integer.class,
        subscription.id(),
        invoice.id());
  }

  private String outboxPayload(String aggregateId, String type) {
    return jdbc.queryForObject(
        "SELECT payload FROM payments.outbox WHERE aggregate_id = ? AND event_type = ?",
        String.class,
        aggregateId,
        type);
  }

  private static Instant retryDay(Instant failedAt, int days) {
    return BillingCalendar.billingInstant(BillingCalendar.today(failedAt).plusDays(days), 3);
  }

  private OutboxMessage event(String type, Order order, Payment payment) {
    String payload = "{\"id\":\"" + payment.id() + "\",\"order_id\":\"" + order.id() + "\"}";

    return new OutboxMessage(
        Ulid.next(),
        merchant,
        payment.id(),
        payment.id(),
        type,
        payload,
        "PENDING",
        null,
        Instant.now());
  }

  private List<DunningAttempt> attemptsOf(Subscription subscription) {
    return attempts.findBySubscription(subscription.id());
  }

  private Order invoiceOf(Subscription subscription) {
    return queries.invoicesOf(merchant, subscription.id()).get(0);
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

  /** The card is saved on a payment for Ana's document; the customer created after adopts it. */
  private Subscription cardSubscription() {
    String cardId = cardSavedFor("Ana Silva", "52998224725");

    return subscribe(ana(), PaymentMethod.CARD, cardId);
  }

  /** Before 03:00 São Paulo the first cycle is not due yet, so the clock moves to it. */
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

  private int jobsFor(String attemptId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.jobs WHERE type = 'DUNNING_RETRY' AND ref_id = ?",
        Integer.class,
        attemptId);
  }

  private List<String> outboxTypes(String aggregateId) {
    return jdbc.queryForList(
        "SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY created_at",
        String.class,
        aggregateId);
  }
}
