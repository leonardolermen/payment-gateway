package com.gateway.app.api.webhook.dto;

import java.time.Instant;

public record RedeliverDeadRequest(Instant since) {}
