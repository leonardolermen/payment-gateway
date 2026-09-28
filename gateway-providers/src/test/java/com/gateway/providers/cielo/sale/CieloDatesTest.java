package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CieloDatesTest {

  /** "ReceivedDate": "2025-11-24 18:04:07" in the creation example: Brasília, UTC-3. */
  @Test
  void aSaleDateIsSaoPauloTime() {
    assertThat(CieloDates.parse("2025-11-24 18:04:07"))
        .isEqualTo(Instant.parse("2025-11-24T21:04:07Z"));
  }

  /** "ReceveidDate": "2024-11-29T13:36:04.033" in the by-order query example. */
  @Test
  void theQueryShapeWithFractionAlsoParses() {
    assertThat(CieloDates.parse("2024-11-29T13:36:04.033"))
        .isEqualTo(Instant.parse("2024-11-29T16:36:04.033Z"));
    assertThat(CieloDates.parse("2025-02-18T14:10:10.61"))
        .isEqualTo(Instant.parse("2025-02-18T17:10:10.610Z"));
  }

  @Test
  void blankIsNull() {
    assertThat(CieloDates.parse(null)).isNull();
    assertThat(CieloDates.parse(" ")).isNull();
  }
}
