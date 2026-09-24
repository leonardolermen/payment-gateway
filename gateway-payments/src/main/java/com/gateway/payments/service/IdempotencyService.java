package com.gateway.payments.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.domain.IdempotencyKey;
import com.gateway.payments.domain.IdempotencyStatus;
import com.gateway.payments.repository.IdempotencyRepository;
import java.time.Clock;
import java.time.Instant;

/**
 * The idempotency-key protocol (spec section 3.2). The row is written BEFORE any bank call and is
 * the lock: a concurrent retry with the same key finds it and gets {@code InProgress} instead of a
 * second charge.
 */
public class IdempotencyService {
  public record Replay(int code, String body) {}

  public sealed interface Outcome {
    record Proceed(IdempotencyKey k) implements Outcome {}

    record Replayed(Replay r) implements Outcome {}

    record InProgress() implements Outcome {}

    record Mismatch() implements Outcome {}
  }

  private final IdempotencyRepository keys;
  private final PaymentsProperties props;
  private final Clock clock;

  public IdempotencyService(IdempotencyRepository keys, PaymentsProperties props, Clock clock) {
    this.keys = keys;
    this.props = props;
    this.clock = clock;
  }

  public Outcome begin(MerchantId merchantId, String key, String requestHash) {
    IdempotencyKey fresh = IdempotencyKey.begin(merchantId, key, requestHash, clock);
    if (keys.insertIfAbsent(fresh)) {
      return new Outcome.Proceed(fresh);
    }
    // The conflicting row can vanish between the insert and this read only if the TTL sweep ran in
    // between; treating that as "try again" is safer than proceeding without a lock.
    IdempotencyKey existing = keys.find(merchantId, key).orElse(null);
    if (existing == null) {
      return new Outcome.InProgress();
    }
    if (!existing.requestHash().equals(requestHash)) {
      return new Outcome.Mismatch();
    }
    if (existing.status() == IdempotencyStatus.IN_PROGRESS) {
      return new Outcome.InProgress();
    }
    return new Outcome.Replayed(new Replay(existing.responseCode(), existing.responseBody()));
  }

  public void finish(IdempotencyKey k, int code, String body, String resourceId) {
    keys.finish(k.finish(code, body, resourceId));
  }

  /** Keys older than {@code idempotencyTtl} stop protecting anything; the sweep keeps the table small. */
  public int purgeExpired(Instant now) {
    return keys.deleteOlderThan(now.minus(props.idempotencyTtl()));
  }
}
