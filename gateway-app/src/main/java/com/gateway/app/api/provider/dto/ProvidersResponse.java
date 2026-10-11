package com.gateway.app.api.provider.dto;

import com.gateway.merchants.apikey.ApiKeyEnvironment;
import java.util.List;

/**
 * {@code inboundWebhookUrl} is what the merchant registers at the bank; null when the mTLS
 * connector is off.
 */
public record ProvidersResponse(
    ApiKeyEnvironment environment,
    String inboundWebhookUrl,
    List<ProviderStatusResponse> providers) {}
