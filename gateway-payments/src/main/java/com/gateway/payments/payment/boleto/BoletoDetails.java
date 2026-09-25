package com.gateway.payments.payment.boleto;

import java.time.LocalDate;

/**
 * The boleto side of a Bolecode. {@code nossoNumero} is ours and is the key of every bank query;
 * the rest arrives from the bank at issue (or from the query, when the issue's answer was lost).
 * {@code paymentLimitDate} is the day the charge expires — the due date only starts interest.
 */
public record BoletoDetails(String nossoNumero, String idBoletoIndividual, String linhaDigitavel, String codigoBarras,
                            LocalDate dueDate, LocalDate paymentLimitDate, PaidVia paidVia) {

  /** A null limit from the bank keeps the one we asked for. */
  public BoletoDetails withIssued(String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate) {
    return new BoletoDetails(nossoNumero, idBoletoIndividual, linhaDigitavel, codigoBarras, dueDate,
        paymentLimitDate == null ? this.paymentLimitDate : paymentLimitDate, paidVia);
  }

  public BoletoDetails withPaidVia(PaidVia via) {
    return new BoletoDetails(nossoNumero, idBoletoIndividual, linhaDigitavel, codigoBarras, dueDate, paymentLimitDate, via);
  }
}
