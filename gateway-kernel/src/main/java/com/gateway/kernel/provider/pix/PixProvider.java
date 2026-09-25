package com.gateway.kernel.provider.pix;

import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderWebhookEvent;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Implemented per bank in {@code gateway-providers} (e.g. ItauPixProvider); consumed by payments. */
public interface PixProvider {
  String id();

  Charge createCharge(ProviderCredentials c, String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description);

  Optional<Charge> findCharge(ProviderCredentials c, String txid);

  void cancelCharge(ProviderCredentials c, String txid);

  RefundResult requestRefund(ProviderCredentials c, RefundRequest r);

  Optional<RefundResult> findRefund(ProviderCredentials c, String endToEndId, String refundId);

  List<Charge> listCharges(ProviderCredentials c, Instant from, Instant to);

  ProviderWebhookEvent parseWebhook(byte[] body);
}
