package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTransitionsTest {
  @Test
  void openGoesToEveryFinalState() {
    assertThat(OrderTransitions.allowed(OrderStatus.OPEN, OrderStatus.PAID)).isTrue();
    assertThat(OrderTransitions.allowed(OrderStatus.OPEN, OrderStatus.CANCELED)).isTrue();
    assertThat(OrderTransitions.allowed(OrderStatus.OPEN, OrderStatus.EXPIRED)).isTrue();
  }

  @Test
  void finalStatesNeverMove() {
    for (OrderStatus from :
        new OrderStatus[] {OrderStatus.PAID, OrderStatus.CANCELED, OrderStatus.EXPIRED}) {
      for (OrderStatus to : OrderStatus.values()) {
        assertThat(OrderTransitions.allowed(from, to)).as(from + "->" + to).isFalse();
      }
    }
  }
}
