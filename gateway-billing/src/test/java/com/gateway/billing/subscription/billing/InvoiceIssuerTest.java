package com.gateway.billing.subscription.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InvoiceIssuerTest {
  private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

  @Test
  void aRunResumedPastTheInvoiceExpiryStillSendsAPositivePixExpiry() {
    assertThat(InvoiceIssuer.pixExpirySeconds(NOW, NOW.minus(Duration.ofHours(2)))).isEqualTo(60);
    assertThat(InvoiceIssuer.pixExpirySeconds(NOW, NOW)).isEqualTo(60);
  }

  @Test
  void aLongInvoiceIsCappedAtADay() {
    assertThat(InvoiceIssuer.pixExpirySeconds(NOW, NOW.plus(Duration.ofDays(30))))
        .isEqualTo(86_400);
  }
}
