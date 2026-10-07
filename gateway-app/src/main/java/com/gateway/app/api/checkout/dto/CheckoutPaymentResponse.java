package com.gateway.app.api.checkout.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.card.CardDetails;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The payer's view of an attempt: what the checkout page needs to show a QR code, a boleto line or
 * a card receipt, and nothing more. Written field by field instead of trimming {@code
 * PaymentResponse}, so a field added to the merchant's view never reaches the browser by default:
 * no provider, tid, authorization code, card id, reference, environment or paid/refunded amounts.
 */
public record CheckoutPaymentResponse(
    String id,
    String method,
    String status,
    Pix pix,
    Boleto boleto,
    Card card,
    Instant paidAt,
    Instant createdAt) {

  /**
   * Explicit name: the global SNAKE_CASE strategy does not split the single-letter "E" of
   * copiaECola.
   */
  public record Pix(@JsonProperty("copia_e_cola") String copiaECola, Instant expiresAt) {}

  public record Boleto(String linhaDigitavel, LocalDate dueDate, LocalDate paymentLimitDate) {}

  /** {@code interestAmount}: the part of the payment's amount that is installment interest. */
  public record Card(String brand, String last4, int installments, long interestAmount) {}

  public static CheckoutPaymentResponse from(Payment payment) {
    return new CheckoutPaymentResponse(
        payment.id(),
        payment.method().name(),
        payment.status().name(),
        pix(payment),
        boleto(payment.boleto()),
        card(payment.card()),
        payment.paidAt(),
        payment.createdAt());
  }

  private static Pix pix(Payment payment) {
    PixDetails pix = payment.pix();
    if (pix == null) {
      return null;
    }

    return new Pix(pix.pixCopiaECola(), payment.expiresAt());
  }

  private static Boleto boleto(BoletoDetails boleto) {
    if (boleto == null) {
      return null;
    }

    return new Boleto(boleto.linhaDigitavel(), boleto.dueDate(), boleto.paymentLimitDate());
  }

  private static Card card(CardDetails card) {
    if (card == null) {
      return null;
    }

    return new Card(card.brand(), card.last4(), card.installments(), card.interestAmount());
  }
}
