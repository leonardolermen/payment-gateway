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
  void canceledAndEndedNeverMove() {
    for (SubscriptionStatus from :
        new SubscriptionStatus[] {SubscriptionStatus.CANCELED, SubscriptionStatus.ENDED}) {
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
