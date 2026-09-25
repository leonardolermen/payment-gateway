package com.gateway.payments.payment;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.refund.Refund;
import com.gateway.payments.outbox.persistence.OutboxRepository;
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
  public void emit(MerchantId merchantId, String type, Payment p) {
    append(merchantId, p.id(), p.id(), type, paymentJson(p));
  }

  /** Same partition as the payment: a refund's events stay ordered with the payment's own. */
  public void emitRefund(MerchantId merchantId, String type, Refund r, Payment p) {
    append(merchantId, r.id(), p.id(), type, refundJson(r));
  }

  private void append(MerchantId merchantId, String aggregateId, String partitionKey, String type, Map<String, Object> body) {
    outbox.append(
        new OutboxMessage(Ulid.next(), merchantId, aggregateId, partitionKey, type, json.writeValueAsString(body), "PENDING", null, clock.instant()));
  }

  /** Public for the contract test in gateway-app that holds it to the REST {@code PaymentResponse}'s key set. */
  public static Map<String, Object> paymentJson(Payment p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id());
    // Same spelling as the REST API (PaymentResponse): one resource, one vocabulary, whichever way it arrives.
    m.put("status", p.status().name());
    m.put("method", p.method().name());
    m.put("provider", p.provider());
    m.put("environment", p.environment().name());
    m.put("amount", p.amount().cents());
    m.put("currency", p.amount().currency());
    m.put("reference", p.reference());
    m.put("description", p.description());
    PixDetails pix = p.pix();
    Map<String, Object> pixJson = new LinkedHashMap<>();
    pixJson.put("txid", pix == null ? p.id() : pix.txid());
    pixJson.put("copia_e_cola", pix == null ? null : pix.pixCopiaECola());
    pixJson.put("location", pix == null ? null : pix.location());
    pixJson.put("end_to_end_id", pix == null ? null : pix.endToEndId());
    m.put("pix", pixJson);
    // Same keys as PaymentResponse.Boleto (gateway-app); null for a Pix payment so the key set is stable.
    BoletoDetails boleto = p.boleto();
    if (boleto == null) {
      m.put("boleto", null);
    } else {
      Map<String, Object> boletoJson = new LinkedHashMap<>();
      boletoJson.put("linha_digitavel", boleto.linhaDigitavel());
      boletoJson.put("codigo_barras", boleto.codigoBarras());
      boletoJson.put("due_date", boleto.dueDate() == null ? null : boleto.dueDate().toString());
      boletoJson.put("payment_limit_date", boleto.paymentLimitDate() == null ? null : boleto.paymentLimitDate().toString());
      boletoJson.put("paid_via", boleto.paidVia() == null ? null : boleto.paidVia().name());
      m.put("boleto", boletoJson);
    }
    m.put("expires_at", iso(p.expiresAt()));
    m.put("paid_at", iso(p.paidAt()));
    m.put("paid_amount", cents(p.paidAmount()));
    m.put("refunded_amount", cents(p.refundedAmount()));
    m.put("created_at", iso(p.createdAt()));
    return m;
  }

  /**
   * {@code state} is one of REQUESTED, PROCESSING, COMPLETED, FAILED or UNKNOWN. UNKNOWN (event
   * {@code refund.unknown}) means the bank never settled it within the polling budget: the amount
   * stays reserved and a later {@code refund.completed} or {@code refund.failed} may still follow.
   */
  static Map<String, Object> refundJson(Refund r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("payment_id", r.paymentId());
    m.put("amount", r.amount().cents());
    m.put("state", r.state().name());
    m.put("reason", r.failureReason());
    m.put("requested_at", iso(r.createdAt()));
    m.put("settled_at", iso(r.settledAt()));
    return m;
  }

  // Instants as ISO-8601 strings explicitly: the default Jackson shape for java.time has changed
  // between major versions, and a merchant parsing webhooks must not see it change under them.
  private static String iso(Instant i) {
    return i == null ? null : i.toString();
  }

  private static Long cents(Money m) {
    return m == null ? null : m.cents();
  }
}
