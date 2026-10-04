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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderAttemptService attempts;
  @Autowired CustomerService customers;

  Customer customer() {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null, null, clock));
  }

  Order open() {
    return orders.create(
        OrderFactory.standalone(
            merchant,
            ProviderEnvironment.TEST,
            Money.brl(5000),
            "order-42",
            null,
            customer().id(),
            null,
            null,
            clock));
  }

  @Test
  void createsOpenAndEmits() {
    Order order = open();

    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.OPEN);
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM payments.outbox WHERE aggregate_id = ?",
                String.class,
                order.id()))
        .containsExactly("order.created");
  }

  @Test
  void anInlinePayerIsReadBackWithTheSameDocument() {
    OrderPayer payer =
        new OrderPayer(
            PersonName.of("Ana Silva"),
            Document.of("529.982.247-25"),
            "ana@example.com",
            new CustomerAddress(
                "Rua A 1", "Centro", "Sao Paulo", Uf.of("SP"), ZipCode.of("01310100")));
    Order created =
        orders.create(
            OrderFactory.standalone(
                merchant,
                ProviderEnvironment.TEST,
                Money.brl(5000),
                "order-43",
                null,
                null,
                payer,
                null,
                clock));

    Order read = orders.get(merchant, created.id());

    assertThat(read.payer().document().digits()).isEqualTo("52998224725");
    assertThat(read.payer().address()).isEqualTo(payer.address());
    assertThat(
            jdbc.queryForObject(
                "SELECT payer::text FROM billing.orders WHERE id = ?", String.class, created.id()))
        .doesNotContain("52998224725");
  }

  @Test
  void cancelWithAPendingPixCancelsItAtTheBankAndClosesTheOrder() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    Order canceled = orders.cancel(merchant, order.id());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
    assertThat(paymentQueries.get(merchant, pix.id()).status()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  void cancelOfAClosedOrderIsAConflict() {
    Order order = open();
    orders.cancel(merchant, order.id());

    assertThatThrownBy(() -> orders.cancel(merchant, order.id()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("ORDER_CLOSED");
  }
}
