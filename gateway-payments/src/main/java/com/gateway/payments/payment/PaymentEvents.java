package com.gateway.payments.payment;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.refund.Refund;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the outbox row for a merchant-visible event. Must run inside the caller's transaction
 * ({@link OutboxRepository#append} is {@code MANDATORY}): the event and the state change it
 * announces commit together or not at all.
 *
 * <p>The payload is the public JSON of the resource, built here by hand as a map rather than by
 * serializing the domain object: the domain carries things merchants must never see (the document
 * hash, the clock), and a field added to the aggregate must not leak into webhooks by accident.
 */
public class PaymentEvents {
  private final OutboxRepository outbox;
  private final Clock clock;
  private final ObjectMapper json = JsonMapper.builder().build();

  public PaymentEvents(OutboxRepository outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  /** {@code partitionKey} is the payment id, so a consumer sees one payment's events in order. */
  public void emit(MerchantId merchantId, String type, Payment payment) {
    append(merchantId, payment.id(), payment.id(), type, paymentJson(payment));
  }

  /** Same partition as the payment: a refund's events stay ordered with the payment's own. */
  public void emitRefund(MerchantId merchantId, String type, Refund refund, Payment payment) {
    append(merchantId, refund.id(), payment.id(), type, refundJson(refund));
  }

  private void append(
      MerchantId merchantId,
      String aggregateId,
      String partitionKey,
      String type,
      Map<String, Object> body) {
    outbox.append(
        new OutboxMessage(
            Ulid.next(),
            merchantId,
            aggregateId,
            partitionKey,
            type,
            json.writeValueAsString(body),
            "PENDING",
            null,
            clock.instant()));
  }

  /**
   * Public for the contract test in gateway-app that holds it to the REST {@code PaymentResponse}'s
   * key set.
   */
  public static Map<String, Object> paymentJson(Payment payment) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", payment.id());
    // Same spelling as the REST API (PaymentResponse): one resource, one vocabulary, whichever way
    // it arrives.
    json.put("status", payment.status().name());
    json.put("method", payment.method().name());
    json.put("provider", payment.provider());
    json.put("environment", payment.environment().name());
    json.put("amount", payment.amount().cents());
    json.put("currency", payment.amount().currency());
    json.put("reference", payment.reference());
    json.put("description", payment.description());
    PixDetails pix = payment.pix();
    Map<String, Object> pixJson = new LinkedHashMap<>();
    pixJson.put("txid", pix == null ? payment.id() : pix.txid());
    pixJson.put("copia_e_cola", pix == null ? null : pix.pixCopiaECola());
    pixJson.put("location", pix == null ? null : pix.location());
    pixJson.put("end_to_end_id", pix == null ? null : pix.endToEndId());
    json.put("pix", pixJson);
    // Same keys as PaymentResponse.Boleto (gateway-app); null for a Pix payment so the key set is
    // stable.
    BoletoDetails boleto = payment.boleto();
    if (boleto == null) {
      json.put("boleto", null);
    } else {
      Map<String, Object> boletoJson = new LinkedHashMap<>();
      boletoJson.put("linha_digitavel", boleto.linhaDigitavel());
      boletoJson.put("codigo_barras", boleto.codigoBarras());
      boletoJson.put("due_date", boleto.dueDate() == null ? null : boleto.dueDate().toString());
      boletoJson.put(
          "payment_limit_date",
          boleto.paymentLimitDate() == null ? null : boleto.paymentLimitDate().toString());
      boletoJson.put("paid_via", boleto.paidVia() == null ? null : boleto.paidVia().name());
      json.put("boleto", boletoJson);
    }
    json.put("expires_at", iso(payment.expiresAt()));
    json.put("paid_at", iso(payment.paidAt()));
    json.put("paid_amount", cents(payment.paidAmount()));
    json.put("refunded_amount", cents(payment.refundedAmount()));
    json.put("created_at", iso(payment.createdAt()));
    return json;
  }

  /**
   * {@code state} is one of REQUESTED, PROCESSING, COMPLETED, FAILED or UNKNOWN. UNKNOWN (event
   * {@code refund.unknown}) means the bank never settled it within the polling budget: the amount
   * stays reserved and a later {@code refund.completed} or {@code refund.failed} may still follow.
   */
  static Map<String, Object> refundJson(Refund refund) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", refund.id());
    json.put("payment_id", refund.paymentId());
    json.put("amount", refund.amount().cents());
    json.put("state", refund.state().name());
    json.put("reason", refund.failureReason());
    json.put("requested_at", iso(refund.createdAt()));
    json.put("settled_at", iso(refund.settledAt()));
    return json;
  }

  // Instants as ISO-8601 strings explicitly: the default Jackson shape for java.time has changed
  // between major versions, and a merchant parsing webhooks must not see it change under them.
  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static Long cents(Money money) {
    return money == null ? null : money.cents();
  }
}
