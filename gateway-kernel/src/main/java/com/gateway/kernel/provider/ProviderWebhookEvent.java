package com.gateway.kernel.provider;

import java.util.List;
import java.util.Map;

/**
 * A parsed inbound webhook body. {@code txidByEndToEndId} lets payments resolve which charge a
 * received Pix belongs to when the provider's payload separates the two (Itaú's {@code pix[]} may
 * omit {@code txid} for static-QR or key-transfer payments — those are not ours).
 */
public record ProviderWebhookEvent(List<ReceivedPix> received, List<RefundResult> refundUpdates, Map<String, String> txidByEndToEndId) {}
