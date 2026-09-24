package com.gateway.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void beginIsInProgressWithoutAResponse() {
    IdempotencyKey k = IdempotencyKey.begin(MerchantId.next(), "key-1", "hash-1", clock);
    assertThat(k.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    assertThat(k.responseCode()).isNull();
    assertThat(k.responseBody()).isNull();
  }

  @Test
  void finishBecomesDoneWithCodeBodyAndResource() {
    IdempotencyKey k = IdempotencyKey.begin(MerchantId.next(), "key-1", "hash-1", clock);
    IdempotencyKey finished = k.finish(201, "{\"id\":\"abc\"}", "abc");
    assertThat(finished.status()).isEqualTo(IdempotencyStatus.DONE);
    assertThat(finished.responseCode()).isEqualTo(201);
    assertThat(finished.responseBody()).isEqualTo("{\"id\":\"abc\"}");
    assertThat(finished.resourceId()).isEqualTo("abc");
  }

  @Test
  void hashOfIsDeterministicShaTwoFiveSixHex() {
    String h1 = IdempotencyKey.hashOf("body-a");
    String h2 = IdempotencyKey.hashOf("body-a");
    String h3 = IdempotencyKey.hashOf("body-b");
    assertThat(h1).isEqualTo(h2);
    assertThat(h1).matches("[0-9a-f]{64}");
    assertThat(h1).isNotEqualTo(h3);
  }
}
