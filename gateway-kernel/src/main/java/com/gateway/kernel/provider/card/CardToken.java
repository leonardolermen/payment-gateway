package com.gateway.kernel.provider.card;

import com.gateway.kernel.security.Secret;

/**
 * A card stored at the acquirer. {@code securityCode} is required: the Cielo's schema for a
 * tokenized charge lists {@code "required": ["CardToken", "SecurityCode"]}
 * (reference/cartao-tokenizado-api), against the spec's "optional" (plan D3).
 *
 * <p>The token itself is a credential for this merchant's account at the Cielo: never printed.
 */
public record CardToken(String value, CardBrand brand, CardOnFileUsage usage, Secret securityCode)
    implements CardSource {

  @Override
  public String toString() {
    return "CardToken[" + brand + ", " + usage + "]";
  }
}
