package com.gateway.kernel.provider;

import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.kernel.provider.pix.RefundResult;
import java.util.List;
import java.util.Map;

/**
 * A parsed inbound webhook body. {@code txidByEndToEndId} lets payments resolve which charge a
 * received Pix belongs to when the provider's payload separates the two (Itaú's {@code pix[]} may
 * omit {@code txid} for static-QR or key-transfer payments — those are not ours). {@code
 * endToEndIdByRefundId} says which received Pix each refund update was nested under: the body is
 * only a hint, and payments needs the e2eid both to scope the update to the right payment and to
 * ask the bank for the refund's real state.
 */
public record ProviderWebhookEvent(
    List<ReceivedPix> received,
    List<RefundResult> refundUpdates,
    Map<String, String> txidByEndToEndId,
    Map<String, String> endToEndIdByRefundId) {

  public ProviderWebhookEvent(
      List<ReceivedPix> received,
      List<RefundResult> refundUpdates,
      Map<String, String> txidByEndToEndId) {
    this(received, refundUpdates, txidByEndToEndId, Map.of());
  }
}
