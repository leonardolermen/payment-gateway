package com.gateway.merchants.domain;

import static org.assertj.core.api.Assertions.*;
import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApiKeyTest {
  static final String PEPPER = "test-pepper";

  @Test void issuesWithEnvironmentPrefixAndStoresOnlyTheHash() {
    ApiKey.Issued issued = ApiKey.issue(MerchantId.next(), ApiKeyEnvironment.LIVE, PEPPER);
    String plain = issued.plainKey().reveal();
    assertThat(plain).startsWith("gk_live_").hasSize(8 + 26);
    assertThat(issued.apiKey().hash()).isEqualTo(ApiKey.hashOf(plain, PEPPER)).doesNotContain(plain);
    assertThat(issued.apiKey().prefix()).isEqualTo(plain.substring(0, 12));
    assertThat(issued.apiKey().active()).isTrue();
  }

  @Test void hashDependsOnPepper() {
    assertThat(ApiKey.hashOf("gk_test_X", "a")).isNotEqualTo(ApiKey.hashOf("gk_test_X", "b"));
  }

  @Test void environmentComesFromThePrefix() {
    assertThat(ApiKey.environmentOf("gk_test_ABC")).contains(ApiKeyEnvironment.TEST);
    assertThat(ApiKey.environmentOf("gk_live_ABC")).contains(ApiKeyEnvironment.LIVE);
    assertThat(ApiKey.environmentOf("sk_ABC")).isEmpty();
  }

  @Test void revokedOrExpiredIsInvalid() {
    ApiKey k = ApiKey.issue(MerchantId.next(), ApiKeyEnvironment.TEST, PEPPER).apiKey();
    Instant now = Instant.now();
    assertThat(k.isValid(now)).isTrue();
    assertThat(k.revoke().isValid(now)).isFalse();
    assertThat(k.expiringAt(now.minusSeconds(1)).isValid(now)).isFalse();
  }
}
