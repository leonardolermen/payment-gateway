package com.gateway.payments.domain;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import java.time.Clock;
import java.time.Instant;

/** A refund request against a completed payment. {@code id} is the id we send the bank as the devolucao id. */
public final class Refund {
  private final String id;
  private final String paymentId;
  private final MerchantId merchantId;
  private final Money amount;
  private final Instant createdAt;

  private RefundState state;
  private Instant settledAt;
  private String failureReason;

  private Refund(String id, String paymentId, MerchantId merchantId, Money amount, Instant createdAt) {
    this.id = id;
    this.paymentId = paymentId;
    this.merchantId = merchantId;
    this.amount = amount;
    this.createdAt = createdAt;
    this.state = RefundState.REQUESTED;
  }

  public static Refund request(String paymentId, MerchantId merchantId, Money amount, Clock clock) {
    return new Refund(Ulid.next(), paymentId, merchantId, amount, clock.instant());
  }

  public void markProcessing() {
    requireNotTerminal();
    this.state = RefundState.PROCESSING;
  }

  public void markCompleted(Instant settledAt) {
    requireNotTerminal();
    this.state = RefundState.COMPLETED;
    this.settledAt = settledAt;
  }

  public void markFailed(String reason) {
    requireNotTerminal();
    this.state = RefundState.FAILED;
    this.failureReason = reason;
  }

  /** Only from REQUESTED/PROCESSING: a refund the bank already settled either way is not "unknown". */
  public void markUnknown(String reason) {
    if (state != RefundState.REQUESTED && state != RefundState.PROCESSING) {
      throw new IllegalStateException("refund " + id + " is " + state + ", not in flight");
    }
    this.state = RefundState.UNKNOWN;
    this.failureReason = reason;
  }

  private void requireNotTerminal() {
    if (state == RefundState.COMPLETED) {
      throw new IllegalStateException("refund " + id + " is already completed");
    }
  }

  public String id() {
    return id;
  }

  public String paymentId() {
    return paymentId;
  }

  public MerchantId merchantId() {
    return merchantId;
  }

  public Money amount() {
    return amount;
  }

  public RefundState state() {
    return state;
  }

  public Instant settledAt() {
    return settledAt;
  }

  public String failureReason() {
    return failureReason;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public static Refund rehydrate(
      String id,
      String paymentId,
      MerchantId merchantId,
      Money amount,
      RefundState state,
      Instant settledAt,
      String failureReason,
      Instant createdAt) {
    Refund r = new Refund(id, paymentId, merchantId, amount, createdAt);
    r.state = state;
    r.settledAt = settledAt;
    r.failureReason = failureReason;
    return r;
  }
}
