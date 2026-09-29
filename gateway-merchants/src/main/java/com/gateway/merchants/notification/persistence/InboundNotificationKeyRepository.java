package com.gateway.merchants.notification.persistence;

import com.gateway.kernel.ids.MerchantId;
import java.util.Optional;

public interface InboundNotificationKeyRepository {
  void upsert(MerchantId merchantId, String provider, String keyHash);

  Optional<String> findHash(MerchantId merchantId, String provider);
}
