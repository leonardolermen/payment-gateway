package com.gateway.billing.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderPayer;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.UnitOfWork;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderRepositoryCheckoutTokenIntegrationTest extends BillingIntegrationTestBase {
  static final String HASH = "a".repeat(64);

  @Autowired OrderRepository orders;
  @Autowired UnitOfWork unitOfWork;

  Order standalone(String hash) {
    OrderPayer payer =
        new OrderPayer(
            PersonName.of("Ana Silva"), Document.of("52998224725"), "ana@example.com", null);

    return OrderFactory.standalone(
        merchant,
        ProviderEnvironment.TEST,
        Money.brl(4990),
        "ref-1",
        "Coffee",
        null,
        payer,
        null,
        hash,
        clock);
  }

  @Test
  void theHashIsStoredAndTheOrderIsFoundByIt() {
    Order order = standalone(HASH);
    unitOfWork.run(() -> orders.insert(order));

    Optional<Order> found = orders.findByCheckoutTokenHash(HASH);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(order.id());
    assertThat(found.get().checkoutTokenHash()).isEqualTo(HASH);
    assertThat(
            jdbc.queryForObject(
                "SELECT checkout_token_hash FROM billing.orders WHERE id = ?",
                String.class,
                order.id()))
        .isEqualTo(HASH);
  }

  @Test
  void anUnknownHashFindsNothing() {
    assertThat(orders.findByCheckoutTokenHash("b".repeat(64))).isEmpty();
  }

  @Test
  void rotationReplacesTheHashAndTheOldOneNoLongerResolves() {
    Order order = standalone(HASH);
    unitOfWork.run(() -> orders.insert(order));
    String newHash = "c".repeat(64);

    order.rotateCheckoutToken(newHash, clock.instant());
    boolean updated = unitOfWork.inTransaction(() -> orders.update(order));

    assertThat(updated).isTrue();
    assertThat(orders.findByCheckoutTokenHash(HASH)).isEmpty();
    assertThat(orders.findByCheckoutTokenHash(newHash)).map(Order::id).contains(order.id());
  }

  @Test
  void anOrderWithoutATokenHasANullHash() {
    Order order = standalone(null);
    unitOfWork.run(() -> orders.insert(order));

    assertThat(orders.findById(order.id())).map(Order::checkoutTokenHash).isEmpty();
  }
}
