package com.gateway.payments.domain;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A Pix charge. Mutable, event-sourced-in-spirit aggregate: every state change produces a
 * {@link PaymentEvent} whose {@code sequence} is the new {@code version} (optimistic lock).
 *
 * <p>{@code txid} is the payment id itself ({@link Ulid#next()} fits the Bacen {@code [a-zA-Z0-9]{26,35}}
 * pattern with no transformation), so a client retrying after a timeout can ask "does this charge
 * already exist?" using the id it already has.
 *
 * <p>{@code create(...)} already produces the initial "created" event: a payment is never observed
 * at version 0 by anything outside this constructor, so there is no window where a caller could see
 * a payment with no history.
 */
public final class Payment {
  private final String id;
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
  private Instant expiresAt;
  private Instant paidAt;
  private Money paidAmount;
  private Money refundedAmount = Money.ZERO_BRL;
  private long version;
  private Instant updatedAt;
  private PaymentEvent createdEvent;

  private Payment(
      String id,
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
    Payment p =
        new Payment(id, merchantId, environment, provider, amount, reference, description, customerDocumentHash, clock.instant(), clock);
    p.expiresAt = clock.instant().plusSeconds(expiresInSeconds);
    p.version = 1;
    p.createdEvent =
        new PaymentEvent(Ulid.next(), id, p.version, "created", EventSource.API, "{\"amount\":" + amount.cents() + "}", p.createdAt);
    return p;
  }

  private PaymentEvent transition(PaymentStatus to, EventSource by, String type, String payload) {
    if (!PaymentTransitions.allowed(status, to, by)) {
      throw new IllegalStateException("transition " + status + "→" + to + " by " + by + " is not allowed");
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
    PaymentEvent event =
        transition(PaymentStatus.PENDING, EventSource.API, "pending", "{\"txid\":" + json(pixDetails.txid()) + "}");
    this.pix = pixDetails;
    this.expiresAt = expiresAt;
    return event;
  }

  public PaymentEvent markFailed(String reason, EventSource by) {
    return transition(PaymentStatus.FAILED, by, "failed", "{\"reason\":" + json(reason) + "}");
  }

  public PaymentEvent markCompleted(String endToEndId, Money paidAmount, Instant paidAt, EventSource by) {
    PaymentEvent event =
        transition(
            PaymentStatus.COMPLETED,
            by,
            "completed",
            "{\"endToEndId\":" + json(endToEndId) + ",\"paidAmount\":" + paidAmount.cents() + "}");
    this.pix = pix.withEndToEndId(endToEndId);
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
   * <p>Only valid once the payment is in a {@linkplain PaymentStatus#terminal() terminal} status —
   * that is the only case the spec defines this for; a webhook arriving mid-flight (e.g. on
   * PENDING) belongs to a real transition instead, not a silent ignore.
   */
  public Optional<PaymentEvent> recordIgnored(String what, EventSource by) {
    if (!status.terminal()) {
      throw new IllegalStateException("recordIgnored requires a terminal status, was " + status);
    }
    return Optional.of(recordEvent("ignored", by, "{\"what\":" + json(what) + "}"));
  }

  /**
   * Escapes a string for embedding in the hand-built JSON payloads this domain writes (no Jackson
   * here — see the module's javadoc). Handles the characters JSON requires escaping plus other
   * control characters, so a quote, backslash or newline in a provider-supplied string (a webhook
   * reason, an end-to-end id) cannot corrupt the audit trail. {@code null} becomes the JSON
   * {@code null} literal, unquoted.
   */
  private static String json(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  public void applyRefund(Money amount) {
    if (status != PaymentStatus.COMPLETED) {
      throw new IllegalStateException("cannot refund a payment in status " + status);
    }
    Money total = refundedAmount.plus(amount);
    if (total.cents() > this.amount.cents()) {
      throw new IllegalArgumentException("refund total " + total.cents() + " exceeds paid amount " + this.amount.cents());
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
   * The "created" event produced by {@link #create}. Only available on a freshly created
   * aggregate — {@link #rehydrate} does not reconstruct it (the events table, not the aggregate,
   * is the source of truth for history once a payment has been persisted and reloaded), so this
   * returns {@code null} on a rehydrated instance.
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
    Payment p =
        new Payment(id, merchantId, environment, provider, amount, reference, description, customerDocumentHash, createdAt, clock);
    p.status = status;
    p.pix = pix;
    p.expiresAt = expiresAt;
    p.paidAt = paidAt;
    p.paidAmount = paidAmount;
    p.refundedAmount = refundedAmount == null ? Money.ZERO_BRL : refundedAmount;
    p.version = version;
    p.updatedAt = updatedAt;
    return p;
  }
}
