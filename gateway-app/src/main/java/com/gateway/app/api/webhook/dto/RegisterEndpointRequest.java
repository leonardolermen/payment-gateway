package com.gateway.app.api.webhook.dto;

import java.util.List;

public record RegisterEndpointRequest(String url, List<String> events) {}
