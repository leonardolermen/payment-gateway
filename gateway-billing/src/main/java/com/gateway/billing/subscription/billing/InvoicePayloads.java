package com.gateway.billing.subscription.billing;

import com.gateway.payments.payment.Payment;
import java.util.LinkedHashMap;
import java.util.Map;

/** The payer-facing part of an invoice event, shared by invoice.created and invoice.updated. */
final class InvoicePayloads {
  private InvoicePayloads() {}

  static Map<String, Object> pixOf(Payment payment) {
    if (payment == null || payment.pix() == null) {
      return null;
    }

    Map<String, Object> pix = new LinkedHashMap<>();
    pix.put("copia_e_cola", payment.pix().pixCopiaECola());

    return pix;
  }

  static Map<String, Object> boletoOf(Payment payment) {
    if (payment == null || payment.boleto() == null) {
      return null;
    }

    Map<String, Object> boleto = new LinkedHashMap<>();
    boleto.put("linha_digitavel", payment.boleto().linhaDigitavel());
    boleto.put("due_date", String.valueOf(payment.boleto().dueDate()));

    return boleto;
  }
}
