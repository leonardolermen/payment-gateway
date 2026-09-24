package com.gateway.app.api.admin.dto;

import com.gateway.merchants.domain.ApiKeyEnvironment;
import java.util.Map;

public record ProviderCredentialRequest(ApiKeyEnvironment environment, Map<String, Object> payload) {}
