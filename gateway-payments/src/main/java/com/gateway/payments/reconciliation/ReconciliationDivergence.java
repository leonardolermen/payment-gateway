package com.gateway.payments.reconciliation;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.Ulid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A disagreement about a payment for a human to settle: the bank against our record (SYSTEM), or a
 * merchant against either (MERCHANT, a dispute). A class, not a record: it moves through {@link
 * DivergenceTransitions} like {@code Order}.
 *
 * <p>Every {@code Instant} is truncated to microseconds on the way in: Postgres keeps micros, and
 * the optimistic update compares the loaded {@code updated_at} by equality, so a nanosecond the
 * database never stored would make every update look stale.
 */
public final class ReconciliationDivergence {
  /** The kind of a MERCHANT row: the bank said nothing, so there is no provider status to store. */
  public static final String DISPUTE_KIND = "DISPUTE";

  private final String id;
  private final String paymentId;
  private final DivergenceOrigin origin;
  private final String gatewayStatus;
  private final String providerStatus;
  private final String detail;
  private final String reason;
  private final String merchantNote;
  private final Instant createdAt;

  private DivergenceStatus status;
  private DivergenceResolution resolution;
  private String resolutionNote;
  private String resolvedBy;
  private Instant resolvedAt;
  private Instant updatedAt;
  private Instant loadedUpdatedAt;

  private ReconciliationDivergence(
      String id,
      String paymentId,
      DivergenceOrigin origin,
      String gatewayStatus,
      String providerStatus,
      String detail,
      String reason,
      String merchantNote,
      Instant createdAt) {
    this.id = id;
    this.paymentId = paymentId;
    this.origin = origin;
    this.gatewayStatus = gatewayStatus;
    this.providerStatus = providerStatus;
    this.detail = detail;
    this.reason = reason;
    this.merchantNote = merchantNote;
    this.createdAt = micros(createdAt);
    this.status = DivergenceStatus.OPEN;
    this.updatedAt = this.createdAt;
  }

  public static ReconciliationDivergence system(
      String paymentId, String gatewayStatus, String kind, String detail, Instant now) {
    return new ReconciliationDivergence(
        Ulid.next(),
        paymentId,
        DivergenceOrigin.SYSTEM,
        gatewayStatus,
        kind,
        detail,
        null,
        null,
        now);
  }

  public static ReconciliationDivergence merchant(
      String paymentId, String gatewayStatus, String reason, String note, Instant now) {
    return new ReconciliationDivergence(
        Ulid.next(),
        paymentId,
        DivergenceOrigin.MERCHANT,
        gatewayStatus,
        DISPUTE_KIND,
        null,
        reason,
        note,
        now);
  }

  public void markUnderReview(Instant at) {
    transition(DivergenceStatus.UNDER_REVIEW, at);
  }

  /**
   * The operator's decision. Never moves money (spec §3): a CONFIRMED double payment is refunded
   * through POST /refunds by the same operator, so the audit shows two acts, not one.
   */
  public void resolve(DivergenceResolution resolution, String note, String by, Instant at) {
    if (!DivergenceResolution.allowedFor(origin).contains(resolution)) {
      throw new DomainException(
          "RESOLUTION_NOT_ALLOWED", resolution + " does not apply to a " + origin + " divergence");
    }

    transition(resolution.toStatus(), at);

    this.resolution = resolution;
    this.resolutionNote = note;
    this.resolvedBy = by;
    this.resolvedAt = this.updatedAt;
  }

  private void transition(DivergenceStatus to, Instant at) {
    if (!DivergenceTransitions.allowed(status, to)) {
      throw new DomainException("DIVERGENCE_CLOSED", "divergence " + id + " is " + status);
    }

    this.status = to;
    this.updatedAt = micros(at);
  }

  private static Instant micros(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
  }

  public String id() {
    return id;
  }

  public String paymentId() {
    return paymentId;
  }

  public DivergenceOrigin origin() {
    return origin;
  }

  public String gatewayStatus() {
    return gatewayStatus;
  }

  /** The kind: the provider's status for SYSTEM rows, {@link #DISPUTE_KIND} for MERCHANT rows. */
  public String providerStatus() {
    return providerStatus;
  }

  public String detail() {
    return detail;
  }

  /** A {@code DisputeReason} name on MERCHANT rows; null on SYSTEM rows. */
  public String reason() {
    return reason;
  }

  public String merchantNote() {
    return merchantNote;
  }

  public DivergenceStatus status() {
    return status;
  }

  public DivergenceResolution resolution() {
    return resolution;
  }

  public String resolutionNote() {
    return resolutionNote;
  }

  public String resolvedBy() {
    return resolvedBy;
  }

  public Instant resolvedAt() {
    return resolvedAt;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  /** The {@code updated_at} this copy was read with: the guard of the optimistic update. */
  public Instant loadedUpdatedAt() {
    return loadedUpdatedAt;
  }

  public static ReconciliationDivergence rehydrate(
      String id,
      String paymentId,
      DivergenceOrigin origin,
      String gatewayStatus,
      String providerStatus,
      String detail,
      String reason,
      String merchantNote,
      DivergenceStatus status,
      DivergenceResolution resolution,
      String resolutionNote,
      String resolvedBy,
      Instant resolvedAt,
      Instant createdAt,
      Instant updatedAt) {
    ReconciliationDivergence divergence =
        new ReconciliationDivergence(
            id,
            paymentId,
            origin,
            gatewayStatus,
            providerStatus,
            detail,
            reason,
            merchantNote,
            createdAt);

    divergence.status = status;
    divergence.resolution = resolution;
    divergence.resolutionNote = resolutionNote;
    divergence.resolvedBy = resolvedBy;
    divergence.resolvedAt = micros(resolvedAt);
    divergence.updatedAt = micros(updatedAt);
    divergence.loadedUpdatedAt = divergence.updatedAt;

    return divergence;
  }
}
