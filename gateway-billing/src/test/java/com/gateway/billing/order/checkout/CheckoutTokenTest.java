package com.gateway.billing.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class CheckoutTokenTest {
  @Test
  void aGeneratedTokenHasThePrefixAndFortyThreeUrlSafeChars() {
    CheckoutToken token = CheckoutToken.generate(new SecureRandom());

    assertThat(token.value()).matches("^chk_[A-Za-z0-9_-]{43}$");
    assertThat(CheckoutToken.parse(token.value())).contains(token);
  }

  @Test
  void twoTokensDiffer() {
    SecureRandom random = new SecureRandom();
    assertThat(CheckoutToken.generate(random)).isNotEqualTo(CheckoutToken.generate(random));
  }

  @Test
  void aMalformedTokenHashesToNothing() {
    assertThat(CheckoutToken.parse("chk_short")).isEmpty();
    assertThat(CheckoutToken.parse("gk_test_" + "a".repeat(43))).isEmpty();
    assertThat(CheckoutToken.parse("chk_" + "a".repeat(42) + "=")).isEmpty();
    assertThat(CheckoutToken.parse(null)).isEmpty();
  }

  @Test
  void toStringNeverShowsTheValue() {
    CheckoutToken token = CheckoutToken.generate(new SecureRandom());
    assertThat(token.toString()).doesNotContain(token.value().substring(4));
  }
}
