package com.gateway.payments.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.payments.domain.OutboxMessage;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PixDetails;
import com.gateway.payments.domain.Refund;
import com.gateway.payments.repository.OutboxRepository;
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

  static Map<String, Object> paymentJson(Payment p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id());
    // Same spelling as the REST API (PaymentResponse): one resource, one vocabulary, whichever way it arrives.
    m.put("status", p.status().name());
    m.put("method", "PIX");
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
    m.put("expires_at", iso(p.expiresAt()));
    m.put("paid_at", iso(p.paidAt()));
    m.put("paid_amount", cents(p.paidAmount()));
    m.put("refunded_amount", cents(p.refundedAmount()));
    m.put("created_at", iso(p.createdAt()));
    return m;
  }

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
