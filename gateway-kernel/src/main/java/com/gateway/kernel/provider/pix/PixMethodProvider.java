package com.gateway.kernel.provider.pix;

import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderWebhookEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The Pix side: the spine ({@code issue}, {@code find}, {@code cancel}) plus what only Pix has —
 * refunds, a listing for reconciliation, and a webhook to parse. Implemented per bank in
 * {@code gateway-providers} (e.g. ItauPixProvider); consumed by payments.
 */
public interface PixMethodProvider extends MethodProvider<PixIssueRequest, Charge, Charge> {
  RefundResult requestRefund(ProviderCredentials credentials, RefundRequest request);

  Optional<RefundResult> findRefund(ProviderCredentials credentials, String endToEndId, String refundId);

  List<Charge> listCharges(ProviderCredentials credentials, Instant from, Instant to);

  /** Parsing a webhook needs no credential: the inbox must not fail because one was rotated. */
  ProviderWebhookEvent parseWebhook(byte[] body);
}
