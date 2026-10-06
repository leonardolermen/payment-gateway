package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProtectedRoutesTest {

  @Test
  void apiRoutesRequireAnApiKey() {
    assertThat(ProtectedRoutes.requiresApiKey("/v1/merchant")).isTrue();
  }

  @Test
  void adminRoutesDoNot() {
    assertThat(ProtectedRoutes.requiresApiKey("/v1/admin/x")).isFalse();
  }

  @Test
  void providerWebhookRoutesDoNot() {
    assertThat(ProtectedRoutes.requiresApiKey("/v1/providers/x")).isFalse();
  }

  @Test
  void actuatorDoesNot() {
    assertThat(ProtectedRoutes.requiresApiKey("/actuator/health")).isFalse();
  }

  @Test
  void checkoutRoutesDoNot() {
    assertThat(ProtectedRoutes.requiresApiKey("/v1/checkout/chk_x")).isFalse();
    assertThat(ProtectedRoutes.requiresApiKey("/v1/checkout/chk_x/payments")).isFalse();
  }

  @Test
  void checkoutIsThePayersPrefixOnly() {
    assertThat(ProtectedRoutes.isCheckout("/v1/checkout/chk_x")).isTrue();
    assertThat(ProtectedRoutes.isCheckout("/v1/checkout/chk_x/payments/pay_1/cancel")).isTrue();
    assertThat(ProtectedRoutes.isCheckout("/v1/checkouts/chk_x")).isFalse();
    assertThat(ProtectedRoutes.isCheckout("/v1/orders/ord_1/checkout-token/rotate")).isFalse();
  }
}
