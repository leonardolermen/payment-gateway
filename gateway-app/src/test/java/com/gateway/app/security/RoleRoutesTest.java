package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.user.Role;
import org.junit.jupiter.api.Test;

class RoleRoutesTest {
  @Test
  void readsAreForEveryoneWritesNeedFinanceAndSettingsNeedOwner() {
    assertThat(RoleRoutes.required("GET", "/v1/orders")).contains(Role.READONLY);
    assertThat(RoleRoutes.required("POST", "/v1/orders")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/payments/01X/refunds")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/webhooks/deliveries/01X/redeliver"))
        .contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/webhooks/endpoints")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("PUT", "/v1/installment-settings")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("DELETE", "/v1/customers/01X")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("PATCH", "/v1/customers/01X")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/invites")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("GET", "/v1/me")).contains(Role.READONLY);
    assertThat(RoleRoutes.required("POST", "/v1/checkout/abc/payments")).isEmpty();
  }

  @Test
  void userOnlyRoutesAreTheAccountOnes() {
    assertThat(RoleRoutes.userOnly("/v1/me")).isTrue();
    assertThat(RoleRoutes.userOnly("/v1/merchant/users/01X")).isTrue();
    assertThat(RoleRoutes.userOnly("/v1/invites")).isTrue();
    assertThat(RoleRoutes.userOnly("/v1/orders")).isFalse();
  }
}
