package com.gateway.app.api.dto;

import java.util.List;

public record RegisterEndpointRequest(String url, List<String> events) {}
