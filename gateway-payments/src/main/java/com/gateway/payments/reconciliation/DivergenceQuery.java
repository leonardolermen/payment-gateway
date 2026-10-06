package com.gateway.payments.reconciliation;

import java.time.Instant;

/**
 * A page of the operator's queue. Every filter is optional (null = any). {@code afterId} is the
 * cursor: the id of the last row of the previous page; ULIDs are time-ordered, so {@code id <
 * afterId} continues the {@code created_at DESC, id DESC} order without an offset that shifts as
 * new rows arrive.
 */
public record DivergenceQuery(
    DivergenceStatus status,
    DivergenceOrigin origin,
    String kind,
    String merchantId,
    Instant since,
    String afterId,
    int limit) {
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public DivergenceQuery {
    if (limit <= 0) {
      limit = DEFAULT_LIMIT;
    }
    limit = Math.min(limit, MAX_LIMIT);
  }
}
