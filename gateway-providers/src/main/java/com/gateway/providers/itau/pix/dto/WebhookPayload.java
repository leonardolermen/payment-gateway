package com.gateway.providers.itau.pix.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Itaú's inbound webhook body: {@code {"pix":[...]}}, one Pix per POST in practice (NOTES.md). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(List<PixItem> pix) {}
