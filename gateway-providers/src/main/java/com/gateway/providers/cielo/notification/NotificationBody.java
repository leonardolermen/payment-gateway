package com.gateway.providers.cielo.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The Post de Notificação body (docs/webhook): three fields, RecurrentPaymentId only for 2/4. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationBody(
    @JsonProperty("PaymentId") String paymentId,
    @JsonProperty("ChangeType") Integer changeType,
    @JsonProperty("RecurrentPaymentId") String recurrentPaymentId) {}
