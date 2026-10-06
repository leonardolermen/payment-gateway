package com.gateway.payments.reconciliation.persistence;

import com.gateway.payments.reconciliation.DivergenceCount;
import com.gateway.payments.reconciliation.DivergenceQuery;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import java.util.List;
import java.util.Optional;

public interface ReconciliationDivergenceRepository {
  /**
   * Inserts {@code divergence} unless the row would break one of the partial unique indexes of V207
   * (one OPEN or UNDER_REVIEW SYSTEM row per payment and kind; one OPEN or UNDER_REVIEW dispute per
   * payment); returns whether it was inserted. The index, not a prior read, decides, so two workers
   * racing on the same mismatch cannot both insert.
   */
  boolean openIfAbsent(ReconciliationDivergence divergence);

  /** OPEN rows of every origin; UNDER_REVIEW ones are already in a human's hands. */
  List<ReconciliationDivergence> open();

  /**
   * Whether an OPEN or UNDER_REVIEW SYSTEM divergence of this kind exists for the payment: a case
   * an operator already holds is not a reason to open another.
   */
  boolean hasOpen(String paymentId, String providerStatus);

  Optional<ReconciliationDivergence> findById(String id);

  /** Ordered {@code created_at DESC, id DESC}; see {@link DivergenceQuery} for the cursor. */
  List<ReconciliationDivergence> find(DivergenceQuery query);

  /**
   * Writes the lifecycle columns only if the row still has the {@code updated_at} the divergence
   * was loaded with; false means someone else decided first.
   */
  boolean update(ReconciliationDivergence divergence);

  Optional<ReconciliationDivergence> findOpenDispute(String paymentId);

  List<ReconciliationDivergence> findByPayment(String paymentId);

  /** OPEN and UNDER_REVIEW rows, grouped by (origin, kind). */
  List<DivergenceCount> countOpenByOriginAndKind();
}
