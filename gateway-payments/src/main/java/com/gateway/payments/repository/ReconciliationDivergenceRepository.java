package com.gateway.payments.repository;

import com.gateway.payments.domain.ReconciliationDivergence;
import java.util.List;

public interface ReconciliationDivergenceRepository {
  void save(ReconciliationDivergence d);

  List<ReconciliationDivergence> open();
}
