package com.gateway.payments.reconciliation;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.dispute.DisputeReason;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

/**
 * The divergence queue: opening (by the system, idempotent by payment and kind; by a merchant, one
 * dispute at a time), and the operator's review and decision.
 */
public class Divergences {
  private static final int MAX_TEXT = 500;

  private final ReconciliationDivergenceRepository divergences;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public Divergences(
      ReconciliationDivergenceRepository divergences, UnitOfWork unitOfWork, Clock clock) {
    this.divergences = divergences;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public boolean isOpen(Payment payment, String providerStatus) {
    return divergences.hasOpen(payment.id(), providerStatus);
  }

  /**
   * Returns whether this call was the one that opened it. A second open while one is still OPEN is
   * not a second problem.
   */
  public boolean open(Payment payment, String providerStatus, String detail) {
    return divergences.openIfAbsent(
        ReconciliationDivergence.system(
            payment.id(),
            payment.status().name(),
            providerStatus,
            trimmed(detail),
            clock.instant()));
  }

  /**
   * The pre-check gives the clear error in the common case; the insert's unique index is what
   * decides when two requests race, and it lands on the same error.
   */
  public ReconciliationDivergence openDispute(Payment payment, DisputeReason reason, String note) {
    if (divergences.findOpenDispute(payment.id()).isPresent()) {
      throw alreadyOpen(payment);
    }

    ReconciliationDivergence dispute =
        ReconciliationDivergence.merchant(
            payment.id(), payment.status().name(), reason.name(), trimmed(note), clock.instant());

    if (!divergences.openIfAbsent(dispute)) {
      throw alreadyOpen(payment);
    }

    return dispute;
  }

  /**
   * {@code by} is accepted for symmetry with {@link #resolve} but not stored: there is no
   * reviewed_by column, and the decision is the act the audit names.
   */
  public ReconciliationDivergence review(String id, String by) {
    return review(id, by, divergence -> {});
  }

  /**
   * {@code afterUpdate} runs in the same transaction, after the update won: whatever it writes (an
   * outbox row) commits with the state change or not at all.
   */
  public ReconciliationDivergence review(
      String id, String by, Consumer<ReconciliationDivergence> afterUpdate) {
    return unitOfWork.inTransaction(
        () -> {
          ReconciliationDivergence divergence = get(id);
          divergence.markUnderReview(clock.instant());

          stored(divergence);
          afterUpdate.accept(divergence);

          return divergence;
        });
  }

  public ReconciliationDivergence resolve(
      String id, DivergenceResolution resolution, String note, String by) {
    return resolve(id, resolution, note, by, divergence -> {});
  }

  /** {@code afterUpdate}: see {@link #review(String, String, Consumer)}. */
  public ReconciliationDivergence resolve(
      String id,
      DivergenceResolution resolution,
      String note,
      String by,
      Consumer<ReconciliationDivergence> afterUpdate) {
    return unitOfWork.inTransaction(
        () -> {
          ReconciliationDivergence divergence = get(id);
          divergence.resolve(resolution, trimmed(note), by, clock.instant());

          stored(divergence);
          afterUpdate.accept(divergence);

          return divergence;
        });
  }

  public ReconciliationDivergence get(String id) {
    return divergences.findById(id).orElseThrow(() -> new NotFoundException("divergence", id));
  }

  /** OPEN and UNDER_REVIEW, grouped by (origin, kind): what the operations metrics report. */
  public List<DivergenceCount> countOpen() {
    return divergences.countOpenByOriginAndKind();
  }

  public List<ReconciliationDivergence> list(DivergenceQuery query) {
    return divergences.find(query);
  }

  private void stored(ReconciliationDivergence divergence) {
    if (!divergences.update(divergence)) {
      throw new DomainException(
          "CONFLICT", "divergence " + divergence.id() + " changed meanwhile; reload and retry");
    }

    divergence.markSaved();
  }

  private static DomainException alreadyOpen(Payment payment) {
    return new DomainException(
        "DISPUTE_ALREADY_OPEN", "payment " + payment.id() + " already has an open dispute");
  }

  private static String trimmed(String text) {
    if (text == null || text.length() <= MAX_TEXT) {
      return text;
    }
    return text.substring(0, MAX_TEXT);
  }
}
