package com.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.support.BillingIntegrationTestBase;
import org.junit.jupiter.api.Test;

class BillingContextTest extends BillingIntegrationTestBase {
  @Test
  void theBillingSchemaExists() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'billing'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }
}
