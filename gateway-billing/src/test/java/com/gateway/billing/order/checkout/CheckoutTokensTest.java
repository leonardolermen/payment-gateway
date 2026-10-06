package com.gateway.billing.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class CheckoutTokensTest {
  /** A fake hasher: the real one is the app's peppered SHA-256; here only the wiring matters. */
  static final TokenHasher REVERSE = token -> new StringBuilder(token).reverse().toString();

  CheckoutTokens tokens = new CheckoutTokens(REVERSE, new SecureRandom());

  @Test
  void issueReturnsTheTokenAndItsHashAndTheHashIsNotTheToken() {
    CheckoutTokens.Issued issued = tokens.issue();

    assertThat(issued.hash()).isEqualTo(REVERSE.hash(issued.token().value()));
    assertThat(issued.hash()).isNotEqualTo(issued.token().value());
  }

  @Test
  void hashOfARawTokenMatchesTheIssuedHash() {
    CheckoutTokens.Issued issued = tokens.issue();

    assertThat(tokens.hashOf(issued.token().value())).contains(issued.hash());
  }

  @Test
  void hashOfGarbageIsEmptyWithoutCallingTheHasher() {
    TokenHasher explodes =
        token -> {
          throw new AssertionError("hasher called for garbage");
        };

    assertThat(new CheckoutTokens(explodes, new SecureRandom()).hashOf("not-a-token")).isEmpty();
  }
}
