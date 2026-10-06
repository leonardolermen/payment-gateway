package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CustomerDocumentHash;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderAttemptServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderAttemptService attempts;
  @Autowired CustomerService customers;
  @Autowired OrderRepository orderRepository;

  Order orderWithAddress() {
    Customer customer =
        customers.create(
            CustomerFactory.fromRequest(
                merchant,
                ProviderEnvironment.TEST,
                "Ana Silva",
                "52998224725",
                null,
                new CustomerAddress.Raw("Rua A 1", "Centro", "Sao Paulo", "SP", "01310100"),
                clock));

    return orders.create(
        OrderFactory.standalone(
            merchant,
            ProviderEnvironment.TEST,
            Money.brl(5000),
            "order-42",
            null,
            customer.id(),
            null,
            null,
            null,
            clock));
  }

  AttemptRequest.CardAttempt cardAttempt() {
    return new AttemptRequest.CardAttempt(
        new CardChoice.NewCard(
            CardDataFactory.from(
                "4024007153763171", "ANA SILVA", "12/2030", "123", null, YearMonth.of(2026, 9)),
            false),
        1,
        true,
        "LOJA");
  }

  Timestamp attemptMarkerOf(Order order) {
    return jdbc.queryForObject(
        "SELECT attempt_in_progress_at FROM billing.orders WHERE id = ?",
        Timestamp.class,
        order.id());
  }

  // Guard exercised: uq_payments_order_active (the CREATED row is committed before the bank call).
  /**
   * Two callers past the job lease, the bank slow to answer: the one that claimed the order
   * charges, the other is refused before reaching the bank at all.
   */
  @Test
  void twoAttemptsWithASlowBankChargeOnceAndRefuseTheOther() throws Exception {
    Order order = orderWithAddress();
    cards.delayNextIssue(Duration.ofMillis(800));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Callable<Object> attempt =
        () -> {
          try {
            return attempts.attempt(order, cardAttempt(), EventSource.SYSTEM);
          } catch (DomainException refused) {
            return refused;
          }
        };

    Future<Object> first = pool.submit(attempt);
    Thread.sleep(200);
    Future<Object> second = pool.submit(attempt);

    assertThat(second.get())
        .isInstanceOf(DomainException.class)
        .extracting(refused -> ((DomainException) refused).code())
        .isEqualTo("ORDER_HAS_ACTIVE_PAYMENT");
    assertThat(first.get()).isInstanceOf(Payment.class);
    assertThat(cards.callsFor(((Payment) first.get()).id()))
        .containsExactly("authorize:" + ((Payment) first.get()).id());
    pool.shutdown();
  }

  // Guard exercised: the attempt marker (no payment row exists yet for the index to see).
  /** Another process is mid-call on this order: its marker, not an index, is what refuses. */
  @Test
  void aFreshAttemptMarkerRefusesTheAttempt() {
    Order order = orderWithAddress();
    jdbc.update(
        "UPDATE billing.orders SET attempt_in_progress_at = ? WHERE id = ?",
        Timestamp.from(clock.instant()),
        order.id());

    assertThatThrownBy(() -> attempts.attempt(order, cardAttempt(), EventSource.SYSTEM))
        .isInstanceOf(OrderHasActivePaymentException.class);
    assertThat(paymentQueries.listByOrder(merchant, order.id())).isEmpty();
  }

  /** A process that died mid-call must not lock the order forever. */
  @Test
  void anExpiredAttemptMarkerDoesNotBlock() {
    Order order = orderWithAddress();
    jdbc.update(
        "UPDATE billing.orders SET attempt_in_progress_at = ? WHERE id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofHours(1))),
        order.id());

    Payment card = attempts.attempt(order, cardAttempt(), EventSource.SYSTEM);

    assertThat(card.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  /**
   * A call slower than the lock: another caller claimed the expired slot, and the slow one's
   * release must not clear the newer marker.
   */
  @Test
  void releasingAnExpiredClaimLeavesTheNewerMarker() {
    Order order = orderWithAddress();
    Duration lock = Duration.ofMinutes(10);
    Instant slow = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant newer = slow.plus(Duration.ofMinutes(11));
    assertThat(orderRepository.claimAttempt(order.id(), slow, lock)).isTrue();
    assertThat(orderRepository.claimAttempt(order.id(), newer, lock)).isTrue();

    orderRepository.releaseAttempt(order.id(), slow);

    assertThat(attemptMarkerOf(order).toInstant()).isEqualTo(newer);
  }

  @Test
  void theMarkerIsReleasedAfterACompletedAttempt() {
    Order order = orderWithAddress();

    attempts.attempt(order, cardAttempt(), EventSource.SYSTEM);

    assertThat(attemptMarkerOf(order)).isNull();
  }

  @Test
  void theMarkerIsReleasedEvenWhenTheBankThrows() {
    Order order = orderWithAddress();
    cards.failNextAuthorizeWith(
        new ProviderException(ProviderException.Code.UNAVAILABLE, 503, "CIELO", "down"));

    assertThatThrownBy(() -> attempts.attempt(order, cardAttempt(), EventSource.SYSTEM))
        .isInstanceOf(RuntimeException.class);

    assertThat(attemptMarkerOf(order)).isNull();
  }

  @Test
  void anAttemptInheritsTheOrdersAmountAndPayer() {
    Order order = orderWithAddress();

    Payment boleto =
        attempts.attempt(order, new AttemptRequest.BolecodeAttempt(null, null), EventSource.API);

    assertThat(boleto.amount()).isEqualTo(Money.brl(5000));
    assertThat(boleto.orderId()).isEqualTo(order.id());
    assertThat(boleto.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void aSecondAttemptWhileOneIsActiveIsRefusedNamingIt() {
    Order order = orderWithAddress();
    Payment first = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    assertThatThrownBy(
            () -> attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API))
        .isInstanceOf(OrderHasActivePaymentException.class)
        .extracting(e -> ((OrderHasActivePaymentException) e).paymentId())
        .isEqualTo(first.id());
  }

  @Test
  void twoAttemptsRacingLeaveExactlyOneActive() throws Exception {
    Order order = orderWithAddress();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    Callable<Object> race =
        () -> {
          go.await();
          try {
            return attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
          } catch (OrderHasActivePaymentException refused) {
            return refused;
          }
        };
    Future<Object> first = pool.submit(race);
    Future<Object> second = pool.submit(race);
    go.countDown();

    long accepted = Stream.of(first.get(), second.get()).filter(Payment.class::isInstance).count();

    assertThat(accepted).isEqualTo(1);
    pool.shutdown();
  }

  @Test
  void afterTheActiveAttemptExpiresAnotherMethodMayTry() {
    Order order = orderWithAddress();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(60), EventSource.API);
    jdbc.update("UPDATE payments.payments SET status = 'EXPIRED' WHERE id = ?", pix.id());

    Payment card =
        attempts.attempt(
            order,
            new AttemptRequest.CardAttempt(
                new CardChoice.NewCard(
                    CardDataFactory.from(
                        "4024007153763171",
                        "ANA SILVA",
                        "12/2030",
                        "123",
                        null,
                        YearMonth.of(2026, 9)),
                    false),
                1,
                true,
                "LOJA"),
            EventSource.API);

    assertThat(card.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  /**
   * Spec §5: a card saved by an attempt on a customer's order is born that customer's. Without it,
   * the card a customer just saved could not pay his subscription (CARD_NOT_OWNED_BY_CUSTOMER):
   * adoption by document ran only when the customer was created, before this card existed.
   */
  @Test
  void aCardSavedOnACustomersOrderBelongsToTheCustomer() {
    Order order = orderWithAddress();

    Payment card =
        attempts.attempt(
            order,
            new AttemptRequest.CardAttempt(
                new CardChoice.NewCard(
                    CardDataFactory.from(
                        "4024007153763171",
                        "ANA SILVA",
                        "12/2030",
                        "123",
                        null,
                        YearMonth.of(2026, 9)),
                    true),
                1,
                true,
                "LOJA"),
            EventSource.API);

    assertThat(card.card().cardId()).isNotNull();
    assertThat(customers.cardsOf(merchant, order.customerId()))
        .extracting(saved -> saved.id())
        .containsExactly(card.card().cardId());
  }

  /**
   * The charge committed before the adoption runs, so a failing adoption must not turn a paid
   * attempt into an error the idempotency filter would replay. A deleted customer cannot stand in
   * for the failure: payerOf refuses it before any charge. A trigger refusing the adoption's UPDATE
   * on payments.cards is a real database failure at exactly that step.
   */
  @Test
  void aFailedAdoptionStillReturnsThePaidAttempt() {
    Order order = orderWithAddress();
    jdbc.execute(
        "CREATE FUNCTION payments.refuse_adoption() RETURNS trigger LANGUAGE plpgsql AS"
            + " $$ BEGIN RAISE EXCEPTION 'adoption refused'; END $$");
    jdbc.execute(
        "CREATE TRIGGER refuse_adoption BEFORE UPDATE ON payments.cards"
            + " FOR EACH ROW EXECUTE FUNCTION payments.refuse_adoption()");

    try {
      Payment card =
          attempts.attempt(
              order,
              new AttemptRequest.CardAttempt(
                  new CardChoice.NewCard(
                      CardDataFactory.from(
                          "4024007153763171",
                          "ANA SILVA",
                          "12/2030",
                          "123",
                          null,
                          YearMonth.of(2026, 9)),
                      true),
                  1,
                  true,
                  "LOJA"),
              EventSource.API);

      assertThat(card.status()).isEqualTo(PaymentStatus.COMPLETED);
      assertThat(customers.cardsOf(merchant, order.customerId())).isEmpty();
    } finally {
      jdbc.execute("DROP TRIGGER refuse_adoption ON payments.cards");
      jdbc.execute("DROP FUNCTION payments.refuse_adoption()");
    }
  }

  /**
   * The order turns PAID only when the relay delivers the settlement; until then the index sees no
   * active attempt, and without the guard a retry would charge the card a second time.
   */
  @Test
  void aCompletedAttemptNotYetSettledRefusesASecondCharge() {
    Order order = orderWithAddress();
    Payment paid =
        attempts.attempt(
            order,
            new AttemptRequest.CardAttempt(
                new CardChoice.NewCard(
                    CardDataFactory.from(
                        "4024007153763171",
                        "ANA SILVA",
                        "12/2030",
                        "123",
                        null,
                        YearMonth.of(2026, 9)),
                    false),
                1,
                true,
                "LOJA"),
            EventSource.API);
    assertThat(paid.status()).isEqualTo(PaymentStatus.COMPLETED);

    assertThatThrownBy(
            () -> attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("ALREADY_PAID");
    assertThat(paymentQueries.listByOrder(merchant, order.id()))
        .extracting(Payment::id)
        .containsExactly(paid.id());
  }

  @Test
  void aStaleOrderObjectCannotOpenAnAttemptAfterTheOrderWasCanceled() {
    Order stale = orderWithAddress();
    orders.cancel(merchant, stale.id());

    assertThatThrownBy(
            () -> attempts.attempt(stale, new AttemptRequest.PixAttempt(600), EventSource.API))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("ORDER_CLOSED");
    assertThat(paymentQueries.listByOrder(merchant, stale.id())).isEmpty();
  }

  @Test
  void aBolecodeForACustomerWithoutAddressIsRefused() {
    Customer customer =
        customers.create(
            CustomerFactory.fromRequest(
                merchant, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null, null, clock));
    Order order =
        orders.create(
            OrderFactory.standalone(
                merchant,
                ProviderEnvironment.TEST,
                Money.brl(5000),
                "order-44",
                null,
                customer.id(),
                null,
                null,
                null,
                clock));

    assertThatThrownBy(
            () ->
                attempts.attempt(
                    order, new AttemptRequest.BolecodeAttempt(null, null), EventSource.API))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("CUSTOMER_ADDRESS_REQUIRED");
  }

  @Test
  void anAttemptOnAnInlinePayerUsesTheOpenedDocument() {
    OrderPayer payer =
        new OrderPayer(
            PersonName.of("Ana Silva"),
            Document.of("52998224725"),
            null,
            new CustomerAddress(
                "Rua A 1", "Centro", "Sao Paulo", Uf.of("SP"), ZipCode.of("01310100")));
    Order order =
        orders.create(
            OrderFactory.standalone(
                merchant,
                ProviderEnvironment.TEST,
                Money.brl(5000),
                "order-45",
                null,
                null,
                payer,
                null,
                null,
                clock));

    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    assertThat(pix.customerDocumentHash()).isEqualTo(CustomerDocumentHash.of("52998224725"));
  }
}
