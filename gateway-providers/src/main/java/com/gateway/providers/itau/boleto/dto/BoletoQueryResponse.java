package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** GET /boletos 200: {@code data[]} plus paging ({@code page} in the schema, {@code pagination} in the example — ignored either way). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoQueryResponse(List<BoletoQueryItem> data) {}
