package com.gateway.merchants.notification;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.security.Secret;
import com.gateway.kernel.security.Sha256;
import com.gateway.merchants.notification.persistence.InboundNotificationKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * The value an acquirer sends back as a fixed header on its notifications — the only proof they
 * came from it when, like the Cielo, it offers neither mTLS nor a signature (spec §8). Compared in
 * constant time, as SHA-256 hashes, so neither timing nor a leaked row reveals it.
 */
public class InboundNotificationKeyService {
  private final InboundNotificationKeyRepository keys;

  public InboundNotificationKeyService(InboundNotificationKeyRepository keys) {
    this.keys = keys;
  }

  public void set(MerchantId merchantId, String provider, Secret key) {
    keys.upsert(merchantId, provider, Sha256.hex(key.reveal()));
  }

  public boolean matches(MerchantId merchantId, String provider, String presented) {
    if (presented == null || presented.isEmpty()) {
      return false;
    }

    Optional<String> stored = keys.findHash(merchantId, provider);
    return stored.isPresent()
        && MessageDigest.isEqual(
            stored.get().getBytes(StandardCharsets.US_ASCII),
            Sha256.hex(presented).getBytes(StandardCharsets.US_ASCII));
  }
}
