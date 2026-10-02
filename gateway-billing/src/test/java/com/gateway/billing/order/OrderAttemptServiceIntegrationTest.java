package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CustomerDocumentHash;
import java.time.YearMonth;
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
            clock));
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
                clock));

    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    assertThat(pix.customerDocumentHash()).isEqualTo(CustomerDocumentHash.of("52998224725"));
  }
}
