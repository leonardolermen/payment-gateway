package com.gateway.billing.order.checkout;

/** Port: the app hashes with the same pepper as API keys; billing must not import merchants. */
public interface TokenHasher {
  String hash(String token);
}
