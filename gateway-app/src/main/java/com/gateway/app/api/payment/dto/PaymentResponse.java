package com.gateway.app.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The merchant's view of a payment, field by field: the aggregate also carries the customer
 * document hash, which must never reach a response. Money is integer cents, like the request.
 * {@code boleto} is null for a Pix payment so the key set is the same for every method
 * (PaymentJsonContractTest holds it to the webhook's).
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
    Boleto boleto,
    Instant expiresAt,
    Instant paidAt,
    Long paidAmount,
    long refundedAmount,
    Instant createdAt) {

  /**
   * Explicit name: the global SNAKE_CASE strategy does not split the single-letter "E" of
   * copiaECola.
   */
  public record Pix(
      String txid,
      @JsonProperty("copia_e_cola") String copiaECola,
      String location,
      String endToEndId) {}

  public record Boleto(
      String linhaDigitavel,
      String codigoBarras,
      LocalDate dueDate,
      LocalDate paymentLimitDate,
      String paidVia) {}

  public static PaymentResponse from(Payment payment) {
    PixDetails pix = payment.pix();
    BoletoDetails boleto = payment.boleto();
    return new PaymentResponse(
        payment.id(),
        payment.status().name(),
        payment.method().name(),
        payment.provider(),
        payment.environment().name(),
        payment.amount().cents(),
        payment.amount().currency(),
        payment.reference(),
        payment.description(),
        pix == null
            ? new Pix(payment.id(), null, null, null)
            : new Pix(pix.txid(), pix.pixCopiaECola(), pix.location(), pix.endToEndId()),
        boleto == null
            ? null
            : new Boleto(
                boleto.linhaDigitavel(),
                boleto.codigoBarras(),
                boleto.dueDate(),
                boleto.paymentLimitDate(),
                boleto.paidVia() == null ? null : boleto.paidVia().name()),
        payment.expiresAt(),
        payment.paidAt(),
        payment.paidAmount() == null ? null : payment.paidAmount().cents(),
        payment.refundedAmount().cents(),
        payment.createdAt());
  }
}
