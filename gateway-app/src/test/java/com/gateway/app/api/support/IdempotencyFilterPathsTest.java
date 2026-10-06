package com.gateway.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Which requests carry the Idempotency-Key protocol. A create or action left out of the pattern
 * would run twice on a client retry; for an order attempt, that is a second charge.
 */
class IdempotencyFilterPathsTest {
  private final IdempotencyFilter filter = new IdempotencyFilter(null, "key", "");

  @ParameterizedTest(name = "{0} {1} -> filtered {2}")
  @CsvSource({
    "POST, /v1/payments, true",
    "POST, /v1/customers, true",
    "POST, /v1/orders, true",
    "POST, /v1/plans, true",
    "POST, /v1/subscriptions, true",
    "POST, /v1/payments/pay_1/cancel, true",
    "POST, /v1/payments/pay_1/refunds, true",
    "POST, /v1/payments/pay_1/capture, true",
    "POST, /v1/payments/pay_1/disputes, true",
    "POST, /v1/orders/ord_1/payments, true",
    "POST, /v1/orders/ord_1/cancel, true",
    "POST, /v1/orders/01ABC/checkout-token/rotate, true",
    "POST, /v1/subscriptions/sub_1/cancel, true",
    "POST, /v1/webhooks/deliveries/dlv_1/redeliver, true",
    "POST, /v1/webhooks/deliveries/redeliver-dead, true",
    "POST, /v1/customers/abc, false",
    "POST, /v1/orders/ord_1/refunds, false",
    "POST, /v1/subscriptions/sub_1/orders, false",
    "POST, /v1/webhooks/endpoints, false",
    "POST, /v1/webhooks/deliveries, false",
    "GET, /v1/orders, false",
    "PATCH, /v1/customers/abc, false",
    "DELETE, /v1/customers/abc, false"
  })
  void filtersExactlyTheIdempotentPosts(String method, String path, boolean filtered) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);

    assertThat(filter.shouldNotFilter(request)).isEqualTo(!filtered);
  }
}
