package com.gateway.app.api.webhook.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.webhookdelivery.domain.DeliveryCursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryCursorCodecTest {
  @Test
  void roundTrips() {
    DeliveryCursor cursor =
        new DeliveryCursor(Instant.parse("2026-10-04T12:00:00.123456Z"), UUID.randomUUID());

    assertThat(DeliveryCursorCodec.decode(DeliveryCursorCodec.encode(cursor))).isEqualTo(cursor);
  }

  @Test
  void garbageIsABadRequest() {
    assertThatThrownBy(() -> DeliveryCursorCodec.decode("not-a-cursor"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("after");
  }
}
