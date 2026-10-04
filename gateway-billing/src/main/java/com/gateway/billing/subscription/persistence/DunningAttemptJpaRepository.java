package com.gateway.billing.subscription.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface DunningAttemptJpaRepository extends JpaRepository<DunningAttemptEntity, String> {
  List<DunningAttemptEntity> findBySubscriptionIdOrderByAttemptAsc(String subscriptionId);

  /** At most one: {@code uq_dunning_pending_order} allows a single pending row per order. */
  Optional<DunningAttemptEntity> findByOrderIdAndOutcomeIsNull(String orderId);

  boolean existsBySubscriptionIdAndOutcomeIsNullAndOrderIdNot(
      String subscriptionId, String orderId);
}
