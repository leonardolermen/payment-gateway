package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.create.CreatePixPayment;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderSettlementIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderAttemptService attempts;
  @Autowired OrderSettlement settlement;
  @Autowired CustomerService customers;

  Order open() {
    String customerId =
        customers
            .create(
                CustomerFactory.fromRequest(
                    merchant,
                    ProviderEnvironment.TEST,
                    "Ana Silva",
                    "52998224725",
                    null,
                    null,
                    clock))
            .id();
    return orders.create(
        OrderFactory.standalone(
            merchant,
            ProviderEnvironment.TEST,
            Money.brl(5000),
            "o",
            null,
            customerId,
            null,
            null,
            null,
            clock));
  }

  OutboxMessage event(String type, Order order, Payment payment) {
    String payload =
        "{\"id\":\""
            + payment.id()
            + "\",\"order_id\":\""
            + order.id()
            + "\",\"paid_at\":\"2026-10-02T12:00:00Z\"}";
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

  Integer divergencesOf(Payment payment, String kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.reconciliation_divergences"
            + " WHERE payment_id = ? AND provider_status = ?",
        Integer.class,
        payment.id(),
        kind);
  }

  @Test
  void aCompletedAttemptPaysTheOrderOnceEvenIfTheEventRepeats() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
    OutboxMessage completed = event("payment.completed", order, pix);

    settlement.on(completed);
    settlement.on(completed);

    Order paid = orders.get(merchant, order.id());
    assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
    assertThat(paid.paidPaymentId()).isEqualTo(pix.id());
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY id",
                String.class,
                order.id()))
        .containsExactly("order.created", "order.paid");
  }

  @Test
  void aSecondCompletedPaymentOpensADoublePaymentDivergence() {
    Order order = open();
    Payment first = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
    // Only one attempt may be active: the first expires (the bank may still settle it later,
    // EXPIRED -> COMPLETED is a real transition), then another tries.
    jdbc.update("UPDATE payments.payments SET status = 'EXPIRED' WHERE id = ?", first.id());
    Payment second = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    settlement.on(event("payment.completed", order, first));
    settlement.on(event("payment.completed", order, second));

    Order paid = orders.get(merchant, order.id());
    assertThat(paid.paidPaymentId()).isEqualTo(first.id());
    assertThat(divergencesOf(second, "DOUBLE_PAYMENT")).isEqualTo(1);
  }

  @Test
  void aPaymentCompletingOnACanceledOrderOpensADivergenceAndLeavesTheOrderCanceled() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
    orders.cancel(merchant, order.id());

    // The bank's later word: the charge was paid before the cancel reached it.
    settlement.on(event("payment.completed", order, pix));

    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.CANCELED);
    assertThat(divergencesOf(pix, "PAID_AFTER_CLOSE")).isEqualTo(1);
  }

  @Test
  void aFailedAttemptLeavesTheOrderOpen() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    settlement.on(event("payment.expired", order, pix));

    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.OPEN);
  }

  @Test
  void anEventWithoutAnOrderIsIgnored() {
    Payment plain =
        paymentService.create(
            new CreatePixPayment(
                merchant, ProviderEnvironment.TEST, Money.brl(100), "x", null, null, null, null));
    OutboxMessage completed =
        new OutboxMessage(
            Ulid.next(),
            merchant,
            plain.id(),
            plain.id(),
            "payment.completed",
            "{\"id\":\"" + plain.id() + "\"}",
            "PENDING",
            null,
            Instant.now());

    settlement.on(completed); // no exception, nothing to do
  }
}
