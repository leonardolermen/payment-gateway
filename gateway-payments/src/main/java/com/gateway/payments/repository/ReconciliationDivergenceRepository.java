package com.gateway.payments.repository;

import com.gateway.payments.domain.ReconciliationDivergence;
import java.util.List;

public interface ReconciliationDivergenceRepository {
  void save(ReconciliationDivergence d);

  /**
   * Inserts {@code d} unless an OPEN divergence for the same (payment, provider status) exists;
   * returns whether it was inserted. Backed by the partial unique index of V201, so two workers
   * racing on the same mismatch cannot both insert.
   */
  boolean openIfAbsent(ReconciliationDivergence d);

  List<ReconciliationDivergence> open();
}
