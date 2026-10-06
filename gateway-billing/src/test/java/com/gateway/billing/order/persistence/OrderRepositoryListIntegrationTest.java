package com.gateway.billing.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderPayer;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.UnitOfWork;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderRepositoryListIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderRepository orders;
  @Autowired UnitOfWork unitOfWork;

  Order inserted(ProviderEnvironment environment) {
    OrderPayer payer =
        new OrderPayer(
            PersonName.of("Ana Silva"), Document.of("52998224725"), "ana@example.com", null);
    Order order =
        OrderFactory.standalone(
            merchant, environment, Money.brl(4990), null, "Coffee", null, payer, null, null, clock);
    unitOfWork.run(() -> orders.insert(order));

    return order;
  }

  @Test
  void pagesNewestFirstByCursorWithoutRepeatingOrSkipping() {
    List<String> created =
        Stream.generate(() -> inserted(ProviderEnvironment.TEST).id()).limit(5).toList();

    List<Order> first = orders.list(merchant, ProviderEnvironment.TEST, null, null, 3);
    List<Order> second =
        orders.list(merchant, ProviderEnvironment.TEST, null, first.getLast().id(), 3);

    assertThat(Stream.concat(first.stream(), second.stream()).map(Order::id))
        .containsExactlyElementsOf(created.reversed());
  }

  @Test
  void filtersByStatusAndStaysInItsEnvironment() {
    Order paid = inserted(ProviderEnvironment.TEST);
    paid.markPaid("pay-1", clock.instant());
    unitOfWork.run(() -> orders.update(paid));
    Order open = inserted(ProviderEnvironment.TEST);
    Order live = inserted(ProviderEnvironment.LIVE);

    assertThat(orders.list(merchant, ProviderEnvironment.TEST, OrderStatus.PAID, null, 10))
        .map(Order::id)
        .containsExactly(paid.id());
    assertThat(orders.list(merchant, ProviderEnvironment.TEST, null, null, 10))
        .map(Order::id)
        .containsExactly(open.id(), paid.id());
    assertThat(orders.list(merchant, ProviderEnvironment.LIVE, null, null, 10))
        .map(Order::id)
        .containsExactly(live.id());
  }
}
