package com.gateway.billing.order.checkout;

import java.security.SecureRandom;
import java.util.Optional;

/** Issues and recognises checkout tokens. The plain token leaves here once, in {@link Issued}. */
public final class CheckoutTokens {
  public record Issued(CheckoutToken token, String hash) {}

  private final TokenHasher hasher;
  private final SecureRandom random;

  public CheckoutTokens(TokenHasher hasher, SecureRandom random) {
    this.hasher = hasher;
    this.random = random;
  }

  public Issued issue() {
    CheckoutToken token = CheckoutToken.generate(random);

    return new Issued(token, hasher.hash(token.value()));
  }

  public Optional<String> hashOf(String raw) {
    return CheckoutToken.parse(raw).map(token -> hasher.hash(token.value()));
  }
}
