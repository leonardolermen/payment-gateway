package com.gateway.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubscriptionTransitionsTest {
  @Test
  void activeAndPastDueMoveBetweenEachOther() {
    assertThat(
            SubscriptionTransitions.allowed(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE))
        .isTrue();
    assertThat(
            SubscriptionTransitions.allowed(SubscriptionStatus.PAST_DUE, SubscriptionStatus.ACTIVE))
        .isTrue();
  }

  @Test
  void bothBillableStatesCanBeCanceledOrEnded() {
    for (SubscriptionStatus from :
        new SubscriptionStatus[] {SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE}) {
      assertThat(SubscriptionTransitions.allowed(from, SubscriptionStatus.CANCELED)).isTrue();
      assertThat(SubscriptionTransitions.allowed(from, SubscriptionStatus.ENDED)).isTrue();
    }
  }

  @Test
  void incompleteLeavesOnceToActiveExpiredOrCanceled() {
    for (SubscriptionStatus to : SubscriptionStatus.values()) {
      boolean expected =
          to == SubscriptionStatus.ACTIVE
              || to == SubscriptionStatus.INCOMPLETE_EXPIRED
              || to == SubscriptionStatus.CANCELED;
      assertThat(SubscriptionTransitions.allowed(SubscriptionStatus.INCOMPLETE, to))
          .as("INCOMPLETE->" + to)
          .isEqualTo(expected);
    }
  }

  @Test
  void nothingGoesBackToIncomplete() {
    for (SubscriptionStatus from : SubscriptionStatus.values()) {
      assertThat(SubscriptionTransitions.allowed(from, SubscriptionStatus.INCOMPLETE))
          .as(from + "->INCOMPLETE")
          .isFalse();
    }
  }

  @Test
  void canceledEndedAndIncompleteExpiredNeverMove() {
    for (SubscriptionStatus from :
        new SubscriptionStatus[] {
          SubscriptionStatus.CANCELED,
          SubscriptionStatus.ENDED,
          SubscriptionStatus.INCOMPLETE_EXPIRED
        }) {
      for (SubscriptionStatus to : SubscriptionStatus.values()) {
        assertThat(SubscriptionTransitions.allowed(from, to)).as(from + "->" + to).isFalse();
      }
    }
  }

  @Test
  void activeToActiveIsNotATransition() {
    assertThat(
            SubscriptionTransitions.allowed(SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE))
        .isFalse();
  }
}
