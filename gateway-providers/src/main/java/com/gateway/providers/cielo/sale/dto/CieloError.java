package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One item of a 4xx body: {@code [{"Code": 322, "Message": "..."}]}
 * (reference/api-errors-code-message). {@code Code} is a number in the example and a string ("00")
 * in the table; read as text either way.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CieloError(
    @JsonProperty("Code") String code, @JsonProperty("Message") String message) {}
