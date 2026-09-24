package com.gateway.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The transition table is the spec (section 3.1). Every allowed row passes; every combination outside it is refused. */
class PaymentTransitionsTest {
  static Stream<Arguments> allowed() {
    return PaymentTransitions.table().stream().flatMap(t -> t.by().stream().map(s -> Arguments.of(t.from(), t.to(), s)));
  }

  @ParameterizedTest
  @MethodSource("allowed")
  void everyRowOfTheTableIsAllowed(PaymentStatus from, PaymentStatus to, EventSource by) {
    assertThat(PaymentTransitions.allowed(from, to, by)).isTrue();
  }

  static Stream<Arguments> everything() {
    return Stream.of(PaymentStatus.values())
        .flatMap(f -> Stream.of(PaymentStatus.values()).flatMap(t -> Stream.of(EventSource.values()).map(s -> Arguments.of(f, t, s))));
  }

  @ParameterizedTest
  @MethodSource("everything")
  void everythingOutsideTheTableIsRefused(PaymentStatus from, PaymentStatus to, EventSource by) {
    boolean inTable = PaymentTransitions.table().stream().anyMatch(t -> t.from() == from && t.to() == to && t.by().contains(by));
    assertThat(PaymentTransitions.allowed(from, to, by)).isEqualTo(inTable);
  }

  /** The bank wins: a late settlement after we expired the charge is a completion, but only from the provider side. */
  @org.junit.jupiter.api.Test
  void expiredCanCompleteOnlyByProviderOrReconciliation() {
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.PROVIDER_WEBHOOK)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.RECONCILIATION)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.API)).isFalse();
    assertThat(EnumSet.of(PaymentStatus.COMPLETED, PaymentStatus.CANCELED, PaymentStatus.FAILED)).allMatch(PaymentStatus::terminal);
  }
}
