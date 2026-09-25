package com.gateway.payments.idempotency;

import com.gateway.payments.support.ServiceIntegrationTestBase;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyServiceIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired IdempotencyService idempotency;

  @Test
  void firstProceedsThenInProgressThenReplays() {
    String hash = IdempotencyKey.hashOf("{\"amount\":100}");

    IdempotencyService.Outcome first = idempotency.begin(merchant, "key-1", hash);
    assertThat(first).isInstanceOf(IdempotencyService.Outcome.Proceed.class);
    assertThat(idempotency.begin(merchant, "key-1", hash)).isInstanceOf(IdempotencyService.Outcome.InProgress.class);

    idempotency.finish(((IdempotencyService.Outcome.Proceed) first).k(), 201, "{\"id\":\"x\"}", null);

    assertThat(idempotency.begin(merchant, "key-1", hash))
        .isEqualTo(new IdempotencyService.Outcome.Replayed(new IdempotencyService.Replay(201, "{\"id\":\"x\"}")));
  }

  @Test
  void sameKeyDifferentBodyIsAMismatch() {
    idempotency.begin(merchant, "key-2", IdempotencyKey.hashOf("a"));

    assertThat(idempotency.begin(merchant, "key-2", IdempotencyKey.hashOf("b"))).isInstanceOf(IdempotencyService.Outcome.Mismatch.class);
  }
}
