package com.gateway.payments.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {
  static final byte[] KEY = "test-hmac-key".getBytes(StandardCharsets.UTF_8);
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
  void hashOfIsADeterministicHmacHex() {
    String first = IdempotencyKey.hashOf("body-a", KEY);
    String again = IdempotencyKey.hashOf("body-a", KEY);
    String other = IdempotencyKey.hashOf("body-b", KEY);
    assertThat(first).isEqualTo(again);
    assertThat(first).matches("[0-9a-f]{64}");
    assertThat(first).isNotEqualTo(other);
  }

  /** The body carries PAN + CVV: without the server key the digest must not be reproducible. */
  @Test
  void theSameBodyUnderAnotherKeyHashesDifferently() {
    assertThat(IdempotencyKey.hashOf("body-a", KEY))
        .isNotEqualTo(
            IdempotencyKey.hashOf("body-a", "other-key".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aMissingKeyIsRefused() {
    assertThatThrownBy(() -> IdempotencyKey.hashOf("body-a", new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
