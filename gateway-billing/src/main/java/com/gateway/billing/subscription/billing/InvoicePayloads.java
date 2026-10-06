package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;
import com.gateway.payments.payment.Payment;
import java.util.LinkedHashMap;
import java.util.Map;

/** The invoice.created and invoice.updated payloads, and the payer-facing part they share. */
public final class InvoicePayloads {
  private InvoicePayloads() {}

  public static Map<String, Object> created(
      Subscription subscription, Order invoice, IssuedInvoice issued, String reason) {
    Map<String, Object> period = new LinkedHashMap<>();
    period.put("start", invoice.periodStart().toString());
    period.put("end", invoice.periodEnd().toString());

    Payment payment = issued.payment();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("invoice_id", invoice.id());
    body.put("subscription_id", subscription.id());
    body.put("invoice_number", invoice.invoiceNumber());
    body.put("amount", invoice.amount().cents());
    body.put("currency", invoice.amount().currency());
    body.put("method", subscription.method().name());
    body.put("period", period);
    body.put("payment_id", issued.paymentId());
    body.put("charged", issued.charged());
    body.put("decline_code", issued.declineCode());
    body.put("reason", reason);
    body.put("pix", pixOf(payment));
    body.put("boleto", boletoOf(payment));

    return body;
  }

  public static Map<String, Object> updated(
      Order invoice, Subscription subscription, int attempt, Payment payment) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("invoice_id", invoice.id());
    body.put("subscription_id", subscription.id());
    body.put("attempt", attempt);
    body.put("payment_id", payment.id());
    body.put("method", subscription.method().name());
    body.put("pix", pixOf(payment));
    body.put("boleto", boletoOf(payment));

    return body;
  }

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
