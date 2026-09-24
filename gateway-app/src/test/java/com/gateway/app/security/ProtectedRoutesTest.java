package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProtectedRoutesTest {

  @Test
  void apiRoutesRequireAnApiKey() {
    assertThat(ProtectedRoutes.requiresApiKey("/v1/me")).isTrue();
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
}
