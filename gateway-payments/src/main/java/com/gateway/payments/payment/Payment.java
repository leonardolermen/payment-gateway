package com.gateway.payments.payment;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A Pix charge or a Bolecode (a registered boleto with a Pix QR on it). Mutable,
 * event-sourced-in-spirit aggregate: every state change produces a {@link PaymentEvent} whose
 * {@code sequence} is the new {@code version} (optimistic lock).
 *
 * <p>{@code txid} is the payment id itself ({@link Ulid#next()} fits the Bacen {@code
 * [a-zA-Z0-9]{26,35}} pattern with no transformation), so a client retrying after a timeout can ask
 * "does this charge already exist?" using the id it already has.
 *
 * <p>{@code create(...)} already produces the initial "created" event: a payment is never observed
 * at version 0 by anything outside this constructor, so there is no window where a caller could see
 * a payment with no history.
 */
public final class Payment {
  private final String id;
  private final PaymentMethod method;
  private final MerchantId merchantId;
  private final ProviderEnvironment environment;
  private final String provider;
  private final Money amount;
  private final String reference;
  private final String description;
  private final String customerDocumentHash;
  private final Instant createdAt;
  private final Clock clock;

  private PaymentStatus status;
  private PixDetails pix;
  private BoletoDetails boleto;
  private Instant expiresAt;
  private Instant paidAt;
  private Money paidAmount;
  private Money refundedAmount = Money.ZERO_BRL;
  private long version;
  private Instant updatedAt;
  private PaymentEvent createdEvent;

  private Payment(
      String id,
      PaymentMethod method,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      Instant createdAt,
      Clock clock) {
    this.id = id;
    this.method = method;
    this.merchantId = merchantId;
    this.environment = environment;
    this.provider = provider;
    this.amount = amount;
    this.reference = reference;
    this.description = description;
    this.customerDocumentHash = customerDocumentHash;
    this.clock = clock;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
    this.status = PaymentStatus.CREATED;
    this.pix = new PixDetails(id, null, null, null);
    this.version = 0;
  }

  public static Payment create(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      int expiresInSeconds,
      Clock clock) {
    String id = Ulid.next();
    Payment payment =
        new Payment(
            id,
            PaymentMethod.PIX,
            merchantId,
            environment,
            provider,
            amount,
            reference,
            description,
            customerDocumentHash,
            clock.instant(),
            clock);
    payment.expiresAt = clock.instant().plusSeconds(expiresInSeconds);
    payment.version = 1;
    payment.createdEvent =
        new PaymentEvent(
            Ulid.next(),
            id,
            payment.version,
            "created",
            EventSource.API,
            "{\"amount\":" + amount.cents() + ",\"method\":\"PIX\"}",
            payment.createdAt);
    return payment;
  }

  /**
   * A Bolecode starts with its nosso número reserved (the bank's query key) and no txid: the Pix
   * side only exists once the bank answers the issue. {@code expiresAt} is the limit date's end of
   * day in São Paulo, computed by the caller.
   */
  public static Payment createBolecode(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      BoletoDetails boleto,
      Instant expiresAt,
      Clock clock) {
    String id = Ulid.next();
    Payment payment =
        new Payment(
            id,
            PaymentMethod.BOLECODE,
            merchantId,
            environment,
            provider,
            amount,
            reference,
            description,
            customerDocumentHash,
            clock.instant(),
            clock);
    payment.pix = new PixDetails(null, null, null, null);
    payment.boleto = boleto;
    payment.expiresAt = expiresAt;
    payment.version = 1;
    payment.createdEvent =
        new PaymentEvent(
            Ulid.next(),
            id,
            payment.version,
            "created",
            EventSource.API,
            "{\"amount\":"
                + amount.cents()
                + ",\"method\":\"BOLECODE\",\"nossoNumero\":"
                + json(boleto.nossoNumero())
                + "}",
            payment.createdAt);
    return payment;
  }

  private PaymentEvent transition(PaymentStatus to, EventSource by, String type, String payload) {
    if (!PaymentTransitions.allowed(status, to, by)) {
      throw new IllegalStateException(
          "transition " + status + "→" + to + " by " + by + " is not allowed");
    }
    this.status = to;
    return recordEvent(type, by, payload);
  }

  private PaymentEvent recordEvent(String type, EventSource by, String payload) {
    version++;
    updatedAt = clock.instant();
    return new PaymentEvent(Ulid.next(), id, version, type, by, payload, updatedAt);
  }

  public PaymentEvent markPending(PixDetails pixDetails, Instant expiresAt) {
    return markPending(pixDetails, expiresAt, EventSource.API);
  }

  public PaymentEvent markPending(PixDetails pixDetails, Instant expiresAt, EventSource by) {
    PaymentEvent event =
        transition(
            PaymentStatus.PENDING, by, "pending", "{\"txid\":" + json(pixDetails.txid()) + "}");
    this.pix = pixDetails;
    this.expiresAt = expiresAt;
    return event;
  }

  public PaymentEvent markPendingBolecode(
      PixDetails pixDetails, BoletoDetails boletoDetails, Instant expiresAt, EventSource by) {
    if (method != PaymentMethod.BOLECODE) {
      throw new IllegalStateException("markPendingBolecode on a " + method + " payment");
    }
    PaymentEvent event =
        transition(
            PaymentStatus.PENDING,
            by,
            "pending",
            "{\"txid\":"
                + json(pixDetails.txid())
                + ",\"nossoNumero\":"
                + json(boletoDetails.nossoNumero())
                + "}");
    this.pix = pixDetails;
    this.boleto = boletoDetails;
    this.expiresAt = expiresAt;
    return event;
  }

  public PaymentEvent markFailed(String reason, EventSource by) {
    return transition(PaymentStatus.FAILED, by, "failed", "{\"reason\":" + json(reason) + "}");
  }

  public PaymentEvent markCompleted(
      String endToEndId, Money paidAmount, Instant paidAt, EventSource by) {
    PaymentEvent event =
        transition(
            PaymentStatus.COMPLETED,
            by,
            "completed",
            "{\"endToEndId\":"
                + json(endToEndId)
                + ",\"paidAmount\":"
                + paidAmount.cents()
                + ",\"paidVia\":\"PIX\"}");
    this.pix = pix.withEndToEndId(endToEndId);
    if (boleto != null) {
      this.boleto = boleto.withPaidVia(PaidVia.PIX);
    }
    this.paidAmount = paidAmount;
    this.paidAt = paidAt;
    return event;
  }

  /**
   * The barcode path: no endToEndId exists, the bank's payment record is the evidence. {@code
   * paidChannel} is its codigo_meio_pagamento.
   */
  public PaymentEvent markCompletedByBoleto(
      Money paidAmount, Instant paidAt, String paidChannel, EventSource by) {
    if (method != PaymentMethod.BOLECODE) {
      throw new IllegalStateException("markCompletedByBoleto on a " + method + " payment");
    }
    PaymentEvent event =
        transition(
            PaymentStatus.COMPLETED,
            by,
            "completed",
            "{\"paidVia\":\"BOLETO\",\"paidAmount\":"
                + paidAmount.cents()
                + ",\"paidChannel\":"
                + json(paidChannel)
                + "}");
    this.boleto = boleto.withPaidVia(PaidVia.BOLETO);
    this.paidAmount = paidAmount;
    this.paidAt = paidAt;
    return event;
  }

  public PaymentEvent markExpired(EventSource by) {
    return transition(PaymentStatus.EXPIRED, by, "expired", "{}");
  }

  public PaymentEvent markCanceled(EventSource by) {
    return transition(PaymentStatus.CANCELED, by, "canceled", "{}");
  }

  /**
   * A webhook that arrives once the payment is already in a state that does not accept it (a
   * duplicate settlement, a late notification after cancellation) is not an error — it is recorded
   * as an "ignored" event, without a state transition. It still bumps {@code version}: it is a
   * stored fact, and {@code sequence} must keep advancing for the event log to stay monotonic.
   *
   * <p>Also valid on PENDING/EXPIRED since the webhook stopped being trusted on its own word: a
   * notification the bank does not confirm, or a Pix of the wrong amount, must leave a trace on the
   * payment without moving it. Only CREATED refuses: nothing can have been paid for a charge the
   * bank has not answered yet.
   */
  public Optional<PaymentEvent> recordIgnored(String what, EventSource by) {
    if (status == PaymentStatus.CREATED) {
      throw new IllegalStateException("recordIgnored is meaningless on a CREATED payment");
    }
    return Optional.of(recordEvent("ignored", by, "{\"what\":" + json(what) + "}"));
  }

  /**
   * Escapes a string for embedding in the hand-built JSON payloads this domain writes (no Jackson
   * here — see the module's javadoc). Handles the characters JSON requires escaping plus other
   * control characters, so a quote, backslash or newline in a provider-supplied string (a webhook
   * reason, an end-to-end id) cannot corrupt the audit trail. {@code null} becomes the JSON {@code
   * null} literal, unquoted.
   */
  private static String json(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder stringBuilder = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> stringBuilder.append("\\\"");
        case '\\' -> stringBuilder.append("\\\\");
        case '\n' -> stringBuilder.append("\\n");
        case '\r' -> stringBuilder.append("\\r");
        case '\t' -> stringBuilder.append("\\t");
        default -> {
          if (c < 0x20) {
            stringBuilder.append(String.format("\\u%04x", (int) c));
          } else {
            stringBuilder.append(c);
          }
        }
      }
    }
    return stringBuilder.append('"').toString();
  }

  /**
   * A refund settled (or failed) at the bank. Recorded as a payment event, not only as a change of
   * {@code refunded_amount}: the event bumps {@code version}, so two concurrent read-modify-writes
   * of {@code refunded_amount} collide on the optimistic lock instead of one silently losing.
   */
  public PaymentEvent recordRefund(
      String refundId, Money amount, boolean completed, EventSource by) {
    if (completed) {
      applyRefund(amount);
    }
    return recordEvent(
        completed ? "refund_completed" : "refund_failed",
        by,
        "{\"refundId\":" + json(refundId) + ",\"amount\":" + amount.cents() + "}");
  }

  public void applyRefund(Money amount) {
    if (status != PaymentStatus.COMPLETED) {
      throw new IllegalStateException("cannot refund a payment in status " + status);
    }
    Money total = refundedAmount.plus(amount);
    if (total.cents() > this.amount.cents()) {
      throw new IllegalArgumentException(
          "refund total " + total.cents() + " exceeds paid amount " + this.amount.cents());
    }
    this.refundedAmount = total;
  }

  public Money refundedAmount() {
    return refundedAmount;
  }

  public boolean fullyRefunded() {
    return refundedAmount.cents() == amount.cents();
  }

  public boolean partiallyRefunded() {
    return !refundedAmount.isZero() && !fullyRefunded();
  }

  public String id() {
    return id;
  }

  public MerchantId merchantId() {
    return merchantId;
  }

  public ProviderEnvironment environment() {
    return environment;
  }

  public String provider() {
    return provider;
  }

  public PaymentStatus status() {
    return status;
  }

  public Money amount() {
    return amount;
  }

  public String reference() {
    return reference;
  }

  public String description() {
    return description;
  }

  public String customerDocumentHash() {
    return customerDocumentHash;
  }

  public PixDetails pix() {
    return pix;
  }

  public PaymentMethod method() {
    return method;
  }

  public BoletoDetails boleto() {
    return boleto;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant paidAt() {
    return paidAt;
  }

  public Money paidAmount() {
    return paidAmount;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  /**
   * The "created" event produced by {@link #create}. Only available on a freshly created aggregate
   * — {@link #rehydrate} does not reconstruct it (the events table, not the aggregate, is the
   * source of truth for history once a payment has been persisted and reloaded), so this returns
   * {@code null} on a rehydrated instance.
   */
  public PaymentEvent createdEvent() {
    return createdEvent;
  }

  public static Payment rehydrate(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      PaymentStatus status,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      PixDetails pix,
      Instant expiresAt,
      Instant paidAt,
      Money paidAmount,
      Money refundedAmount,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Clock clock) {
    return rehydrate(
        id,
        merchantId,
        environment,
        provider,
        PaymentMethod.PIX,
        status,
        amount,
        reference,
        description,
        customerDocumentHash,
        pix,
        null,
        expiresAt,
        paidAt,
        paidAmount,
        refundedAmount,
        version,
        createdAt,
        updatedAt,
        clock);
  }

  public static Payment rehydrate(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      PaymentMethod method,
      PaymentStatus status,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      PixDetails pix,
      BoletoDetails boleto,
      Instant expiresAt,
      Instant paidAt,
      Money paidAmount,
      Money refundedAmount,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Clock clock) {
    Payment payment =
        new Payment(
            id,
            method,
            merchantId,
            environment,
            provider,
            amount,
            reference,
            description,
            customerDocumentHash,
            createdAt,
            clock);
    payment.status = status;
    payment.pix = pix;
    payment.boleto = boleto;
    payment.expiresAt = expiresAt;
    payment.paidAt = paidAt;
    payment.paidAmount = paidAmount;
    payment.refundedAmount = refundedAmount == null ? Money.ZERO_BRL : refundedAmount;
    payment.version = version;
    payment.updatedAt = updatedAt;
    return payment;
  }
}
