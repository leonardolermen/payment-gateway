package com.gateway.payments.domain;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RefundTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void requestGeneratesAnIdAndStartsRequested() {
    Refund r = Refund.request("payment-1", MerchantId.next(), Money.brl(5000), clock);
    assertThat(r.id()).matches("[a-zA-Z0-9]{26,35}");
    assertThat(r.state()).isEqualTo(RefundState.REQUESTED);
  }

  @Test
  void markProcessingMovesToProcessing() {
    Refund r = Refund.request("payment-1", MerchantId.next(), Money.brl(5000), clock);
    r.markProcessing();
    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
  }

  @Test
  void markCompletedRecordsSettledAt() {
    Refund r = Refund.request("payment-1", MerchantId.next(), Money.brl(5000), clock);
    r.markProcessing();
    Instant settledAt = Instant.parse("2026-09-24T13:00:00Z");
    r.markCompleted(settledAt);
    assertThat(r.state()).isEqualTo(RefundState.COMPLETED);
    assertThat(r.settledAt()).isEqualTo(settledAt);
  }

  @Test
  void markFailedRecordsReason() {
    Refund r = Refund.request("payment-1", MerchantId.next(), Money.brl(5000), clock);
    r.markProcessing();
    r.markFailed("provider rejected");
    assertThat(r.state()).isEqualTo(RefundState.FAILED);
    assertThat(r.failureReason()).isEqualTo("provider rejected");
  }

  @Test
  void completedDoesNotGoBack() {
    Refund r = Refund.request("payment-1", MerchantId.next(), Money.brl(5000), clock);
    r.markProcessing();
    r.markCompleted(Instant.parse("2026-09-24T13:00:00Z"));
    assertThatThrownBy(() -> r.markProcessing()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> r.markFailed("too late")).isInstanceOf(IllegalStateException.class);
  }
}
