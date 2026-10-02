package com.gateway.billing.subscription.persistence;

import com.gateway.billing.subscription.DunningAttempt;
import java.util.List;
import java.util.Optional;

public interface DunningAttemptRepository {
  /** Requires a transaction. */
  void insert(DunningAttempt attempt);

  /** Last write wins: an attempt is written by its one job, never by two writers at once. */
  void update(DunningAttempt attempt);

  Optional<DunningAttempt> findById(String id);

  /** Oldest attempt first. */
  List<DunningAttempt> findBySubscription(String subscriptionId);

  /** The attempt scheduled for this order that has not run yet. */
  Optional<DunningAttempt> findPendingByOrder(String orderId);
}
