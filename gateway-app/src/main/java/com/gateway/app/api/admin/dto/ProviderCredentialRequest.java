package com.gateway.app.api.admin.dto;

import com.gateway.merchants.apikey.ApiKeyEnvironment;
import java.util.Map;

public record ProviderCredentialRequest(ApiKeyEnvironment environment, Map<String, Object> payload) {}
