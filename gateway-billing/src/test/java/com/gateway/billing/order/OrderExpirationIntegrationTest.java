package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderExpirationIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderAttemptService attempts;
  @Autowired OrderExpiration expiration;
  @Autowired CustomerService customers;

  Order expiringInAnHour() {
    String customerId =
        customers
            .create(
                CustomerFactory.fromRequest(
                    merchant, ProviderEnvironment.TEST, "Ana", "52998224725", null, null, clock))
            .id();
    return orders.create(
        OrderFactory.standalone(
            merchant,
            ProviderEnvironment.TEST,
            Money.brl(100),
            "o",
            null,
            customerId,
            null,
            clock.instant().plus(Duration.ofHours(1)),
            clock));
  }

  @Test
  void creatingAnOrderWithExpiryQueuesItsJob() {
    Order order = expiringInAnHour();

    Integer queued =
        jdbc.queryForObject(
            "SELECT count(*) FROM payments.jobs WHERE type = 'EXPIRE_ORDER' AND ref_id = ?",
            Integer.class,
            order.id());

    assertThat(queued).isEqualTo(1);
  }

  @Test
  void anOpenOrderWithoutAnActiveAttemptExpires() {
    Order order = expiringInAnHour();
    clock.advance(Duration.ofHours(2));

    boolean done = expiration.expireOne(order.id(), clock.instant());

    assertThat(done).isTrue();
    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
  }

  @Test
  void anOrderWithAnActiveAttemptWaitsForIt() {
    Order order = expiringInAnHour();
    attempts.attempt(order, new AttemptRequest.PixAttempt(7200), EventSource.API);
    clock.advance(Duration.ofHours(2));

    boolean done = expiration.expireOne(order.id(), clock.instant());

    assertThat(done).isFalse();
    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.OPEN);
  }

  @Test
  void aPaidOrderIsLeftAlone() {
    Order order = expiringInAnHour();
    jdbc.update("UPDATE billing.orders SET status = 'PAID' WHERE id = ?", order.id());
    clock.advance(Duration.ofHours(2));

    assertThat(expiration.expireOne(order.id(), clock.instant())).isTrue();
    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.PAID);
  }
}
