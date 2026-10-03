package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class OrderFactoryTest {
  static final MerchantId MERCHANT = MerchantId.next();

  @Test
  void aStandaloneOrderNeedsExactlyOneOfCustomerAndPayer() {
    assertThatThrownBy(
            () ->
                OrderFactory.standalone(
                    MERCHANT,
                    ProviderEnvironment.TEST,
                    Money.brl(1000),
                    "r",
                    null,
                    null,
                    null,
                    null,
                    Clock.systemUTC()))
        .hasMessageContaining("customer_id or customer");
  }

  @Test
  void aStandaloneOrderWithACustomerStartsOpenAtVersionOne() {
    Order order =
        OrderFactory.standalone(
            MERCHANT,
            ProviderEnvironment.TEST,
            Money.brl(1000),
            "r",
            null,
            "01CUSTOMER",
            null,
            null,
            Clock.systemUTC());

    assertThat(order.status()).isEqualTo(OrderStatus.OPEN);
    assertThat(order.version()).isEqualTo(1L);
    assertThat(order.isInvoice()).isFalse();
  }
}
