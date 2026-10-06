package com.gateway.payments.reconciliation;

import static com.gateway.payments.reconciliation.DivergenceResolution.CONFIRMED;
import static com.gateway.payments.reconciliation.DivergenceResolution.FALSE_POSITIVE;
import static com.gateway.payments.reconciliation.DivergenceStatus.OPEN;
import static com.gateway.payments.reconciliation.DivergenceStatus.REJECTED;
import static com.gateway.payments.reconciliation.DivergenceStatus.RESOLVED;
import static com.gateway.payments.reconciliation.DivergenceStatus.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DivergenceTransitionsTest {

  @Test
  void openMovesToReviewOrToAFinalState() {
    assertThat(DivergenceTransitions.allowed(OPEN, UNDER_REVIEW)).isTrue();
    assertThat(DivergenceTransitions.allowed(OPEN, RESOLVED)).isTrue();
    assertThat(DivergenceTransitions.allowed(OPEN, REJECTED)).isTrue();
    assertThat(DivergenceTransitions.allowed(UNDER_REVIEW, RESOLVED)).isTrue();
    assertThat(DivergenceTransitions.allowed(UNDER_REVIEW, REJECTED)).isTrue();
  }

  @Test
  void finalStatesNeverMoveAndReviewDoesNotReopen() {
    for (DivergenceStatus from : List.of(RESOLVED, REJECTED)) {
      for (DivergenceStatus to : DivergenceStatus.values()) {
        assertThat(DivergenceTransitions.allowed(from, to)).as(from + "->" + to).isFalse();
      }
    }

    assertThat(DivergenceTransitions.allowed(UNDER_REVIEW, OPEN)).isFalse();
  }

  @Test
  void aResolutionBelongsToAnOrigin() {
    assertThat(DivergenceResolution.allowedFor(DivergenceOrigin.SYSTEM))
        .containsExactlyInAnyOrder(CONFIRMED, FALSE_POSITIVE);
    assertThat(DivergenceResolution.allowedFor(DivergenceOrigin.MERCHANT))
        .containsExactlyInAnyOrder(DivergenceResolution.RESOLVED, DivergenceResolution.REJECTED);

    assertThat(DivergenceResolution.REJECTED.toStatus()).isEqualTo(DivergenceStatus.REJECTED);
    assertThat(CONFIRMED.toStatus()).isEqualTo(DivergenceStatus.RESOLVED);
  }
}
