package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.AttemptRequest;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderExpiration;
import com.gateway.billing.order.OrderSettlement;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.order.checkout.CheckoutService;
import com.gateway.billing.order.checkout.CheckoutView;
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
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.security.Sha256;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardDeclinedException;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec 2026-10-07 §2–3, at the module level: the cycle's code with the recording card provider. */
class SubscriptionByLinkIntegrationTest extends BillingIntegrationTestBase {
  private static final String LINK_BASE = "https://pay.test/pay/";

  @Autowired SubscriptionCreation creation;
  @Autowired SubscriptionService service;
  @Autowired SubscriptionQueries queries;
  @Autowired SubscriptionBilling billing;
  @Autowired CustomerService customers;
  @Autowired PlanService plans;
  @Autowired CheckoutService checkout;
  @Autowired OrderSettlement settlement;
  @Autowired OrderExpiration expiration;

  @Test
  void aCardWithoutACardIdWaitsIncompleteWithItsFirstInvoiceAndALink() {
    SubscriptionCreation.Created created = createIncomplete();

    Subscription subscription = created.subscription();
    assertThat(subscription.status()).isEqualTo(SubscriptionStatus.INCOMPLETE);
    assertThat(subscription.cardId()).isNull();
    assertThat(subscription.lastInvoiceNumber()).isEqualTo(1);
    String url = created.firstInvoice().checkoutUrl();
    assertThat(url).startsWith(LINK_BASE + "chk_");

    Order invoice = invoicesOf(subscription).getFirst();
    assertThat(invoice.id()).isEqualTo(created.firstInvoice().orderId());
    assertThat(invoice.status()).isEqualTo(OrderStatus.OPEN);
    assertThat(invoice.checkoutTokenHash()).isEqualTo(Sha256.hex(tokenOf(url)));
    // No cycle job while nobody can be charged; the expiry of the invoice is scheduled.
    assertThat(jobsFor("BILL_SUBSCRIPTION", subscription.id())).isZero();
    assertThat(jobsFor("EXPIRE_ORDER", invoice.id())).isEqualTo(1);
    assertThat(outboxTypes(subscription.id())).contains("subscription.created");
    assertThat(outboxTypes(invoice.id())).contains("order.created", "invoice.created");
    assertThat(outboxPayload(invoice.id(), "invoice.created"))
        .contains("\"checkout_url\":\"" + url + "\"");
  }

  @Test
  void theCheckoutOfTheFirstInvoiceTakesOnlyANewCardAndSavesIt() {
    String token = tokenOf(createIncomplete().firstInvoice().checkoutUrl());

    CheckoutView view = checkout.get(token);
    assertThat(view.savesCardForSubscription()).isTrue();
    assertThat(view.invoiceTerms().planName()).isEqualTo("Pro");

    assertThatThrownBy(() -> checkout.attempt(token, new AttemptRequest.PixAttempt(600)))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo("CHECKOUT_CARD_REQUIRED"));

    Payment paid = checkout.attempt(token, cardAttempt(false));
    assertThat(paid.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(paid.card().cardId()).isNotNull();
  }

  @Test
  void thePaidFirstInvoiceActivatesWithItsCardAndTheNextCycleChargesIt() {
    SubscriptionCreation.Created created = createIncomplete();
    Order invoice = invoicesOf(created.subscription()).getFirst();
    Payment paid =
        checkout.attempt(tokenOf(created.firstInvoice().checkoutUrl()), cardAttempt(false));

    settlement.on(event("payment.completed", invoice, paid));

    Subscription active = queries.get(merchant, created.subscription().id());
    assertThat(active.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(active.cardId()).isEqualTo(paid.card().cardId());
    assertThat(jobsFor("BILL_SUBSCRIPTION", active.id())).isEqualTo(1);
    assertThat(outboxTypes(active.id())).contains("subscription.activated");

    clock.advance(Duration.between(clock.instant(), active.nextBillingAt()));
    billing.billOne(active.id(), clock.instant());

    Order second = invoicesOf(active).getFirst();
    assertThat(second.invoiceNumber()).isEqualTo(2);
    assertThat(paymentQueries.listByOrder(merchant, second.id()))
        .singleElement()
        .satisfies(
            payment -> {
              assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
              assertThat(payment.card().cardId()).isEqualTo(paid.card().cardId());
            });
    assertThat(outboxPayload(second.id(), "invoice.created"))
        .contains("\"checkout_url\":\"" + LINK_BASE);
  }

  @Test
  void aDeclinedFirstAttemptStartsNoDunning() {
    SubscriptionCreation.Created created = createIncomplete();
    Order invoice = invoicesOf(created.subscription()).getFirst();
    cards.nextAuthorizeStatus(CardStatus.DENIED);

    String token = tokenOf(created.firstInvoice().checkoutUrl());
    CardDeclinedException declined =
        org.junit.jupiter.api.Assertions.assertThrows(
            CardDeclinedException.class, () -> checkout.attempt(token, cardAttempt(true)));
    settlement.on(
        event("payment.failed", invoice, paymentQueries.get(merchant, declined.paymentId())));

    assertThat(queries.get(merchant, created.subscription().id()).status())
        .isEqualTo(SubscriptionStatus.INCOMPLETE);
    assertThat(dunningRowsOf(created.subscription().id())).isZero();
  }

  @Test
  void anExpiredFirstInvoiceEndsTheSubscriptionWithoutDunning() {
    SubscriptionCreation.Created created = createIncomplete();
    Order invoice = invoicesOf(created.subscription()).getFirst();
    clock.advance(Duration.between(clock.instant(), invoice.expiresAt()).plusSeconds(1));

    assertThat(expiration.expireOne(invoice.id(), clock.instant())).isTrue();

    Subscription expired = queries.get(merchant, created.subscription().id());
    assertThat(expired.status()).isEqualTo(SubscriptionStatus.INCOMPLETE_EXPIRED);
    assertThat(expired.nextBillingAt()).isNull();
    assertThat(dunningRowsOf(expired.id())).isZero();
    assertThat(jobsFor("BILL_SUBSCRIPTION", expired.id())).isZero();
  }

  @Test
  void theMerchantCancelsAnIncompleteSubscriptionAndItsInvoice() {
    SubscriptionCreation.Created created = createIncomplete();

    // at_period_end is ignored: there is no paid period to finish.
    Subscription canceled = service.cancel(merchant, created.subscription().id(), true);

    assertThat(canceled.status()).isEqualTo(SubscriptionStatus.CANCELED);
    assertThat(queries.get(merchant, canceled.id()).status())
        .isEqualTo(SubscriptionStatus.CANCELED);
    assertThat(invoicesOf(canceled).getFirst().status()).isEqualTo(OrderStatus.CANCELED);
  }

  @Test
  void aPixSubscriptionIsBornActiveWithNoFirstInvoice() {
    Customer ana = ana();
    SubscriptionCreation.Created created =
        creation.create(
            SubscriptionFactory.fromRequest(
                merchant,
                ProviderEnvironment.TEST,
                ana,
                monthly(),
                PaymentMethod.PIX,
                null,
                BillingCalendar.today(clock.instant()),
                clock),
            ana);

    assertThat(created.subscription().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(created.firstInvoice()).isNull();
    assertThat(jobsFor("BILL_SUBSCRIPTION", created.subscription().id())).isEqualTo(1);
  }

  private SubscriptionCreation.Created createIncomplete() {
    Customer ana = ana();

    return creation.create(
        SubscriptionFactory.fromRequest(
            merchant,
            ProviderEnvironment.TEST,
            ana,
            monthly(),
            PaymentMethod.CARD,
            null,
            BillingCalendar.today(clock.instant()),
            clock),
        ana);
  }

  private static AttemptRequest cardAttempt(boolean save) {
    return new AttemptRequest.CardAttempt(
        new CardChoice.NewCard(
            CardDataFactory.from(
                "4024007153763171", "ANA SILVA", "12/2030", "123", null, YearMonth.of(2026, 9)),
            save),
        1,
        true,
        null);
  }

  private static String tokenOf(String url) {
    return url.substring(LINK_BASE.length());
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

  private List<Order> invoicesOf(Subscription subscription) {
    return queries.invoicesOf(merchant, subscription.id());
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

  private int jobsFor(String type, String refId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.jobs WHERE type = ? AND ref_id = ?",
        Integer.class,
        type,
        refId);
  }

  private int dunningRowsOf(String subscriptionId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM billing.dunning_attempts WHERE subscription_id = ?",
        Integer.class,
        subscriptionId);
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
