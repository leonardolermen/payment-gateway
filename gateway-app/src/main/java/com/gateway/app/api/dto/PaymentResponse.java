package com.gateway.app.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Instant;

/**
 * The merchant's view of a payment, field by field: the aggregate also carries the customer
 * document hash, which must never reach a response. Money is integer cents, like the request.
 */
public record PaymentResponse(
    String id,
    String status,
    String method,
    String provider,
    String environment,
    long amount,
    String currency,
    String reference,
    String description,
    Pix pix,
    Instant expiresAt,
    Instant paidAt,
    Long paidAmount,
    long refundedAmount,
    Instant createdAt) {

  /** Explicit name: the global SNAKE_CASE strategy does not split the single-letter "E" of copiaECola. */
  public record Pix(String txid, @JsonProperty("copia_e_cola") String copiaECola, String location, String endToEndId) {}

  public static PaymentResponse from(Payment p) {
    PixDetails pix = p.pix();
    return new PaymentResponse(
        p.id(),
        p.status().name(),
        "PIX",
        p.provider(),
        p.environment().name(),
        p.amount().cents(),
        p.amount().currency(),
        p.reference(),
        p.description(),
        pix == null ? new Pix(p.id(), null, null, null) : new Pix(pix.txid(), pix.pixCopiaECola(), pix.location(), pix.endToEndId()),
        p.expiresAt(),
        p.paidAt(),
        p.paidAmount() == null ? null : p.paidAmount().cents(),
        p.refundedAmount().cents(),
        p.createdAt());
  }
}
