package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What PUT …/capture and PUT …/void answer: the new status and codes, nothing about amounts
 * (reference/capturar-apos-autorizacao, reference/cancelamento-paymentid).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleUpdateResponse(
    @JsonProperty("Status") Integer status,
    @JsonProperty("ReturnCode") String returnCode,
    @JsonProperty("ReturnMessage") String returnMessage,
    @JsonProperty("Tid") String tid,
    @JsonProperty("ProofOfSale") String proofOfSale,
    @JsonProperty("AuthorizationCode") String authorizationCode) {}
