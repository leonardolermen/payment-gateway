package com.gateway.app.api.checkout;

import com.gateway.billing.order.checkout.TokenHasher;
import com.gateway.merchants.MerchantsProperties;
import com.gateway.merchants.apikey.ApiKey;
import org.springframework.stereotype.Component;

/** The API-key pepper, through the API-key hash: one secret to rotate, one algorithm to audit. */
@Component
public class PepperedTokenHasher implements TokenHasher {
  private final String pepper;

  public PepperedTokenHasher(MerchantsProperties properties) {
    this.pepper = properties.apiKeyPepper();
  }

  @Override
  public String hash(String token) {
    return ApiKey.hashOf(token, pepper);
  }
}
