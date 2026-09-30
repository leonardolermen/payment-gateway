package com.gateway.payments.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundReservationTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

  Refund refund(long cents) {
    return Refund.request("payment-1", MerchantId.next(), Money.brl(cents), clock);
  }

  Refund failed(long cents) {
    Refund refund = refund(cents);
    refund.markFailed("declined");
    return refund;
  }

  static void assertExceeds(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
    assertThatThrownBy(call)
        .isInstanceOf(DomainException.class)
        .hasMessageContaining(message)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }

  @Test
  void aPartialRequestIsGrantedAsAsked() {
    Money amount =
        RefundReservation.reserve(
            List.of(refund(3000)), Money.brl(10000), Money.brl(2000), "paid amount");

    assertThat(amount).isEqualTo(Money.brl(2000));
  }

  @Test
  void noAmountTakesTheExactRemainderAndFailedRefundsDoNotCount() {
    Money amount =
        RefundReservation.reserve(
            List.of(refund(3000), failed(5000)), Money.brl(10000), null, "paid amount");

    assertThat(amount).isEqualTo(Money.brl(7000));
  }

  @Test
  void morethanTheRemainderExceeds() {
    assertExceeds(
        () ->
            RefundReservation.reserve(
                List.of(refund(3000)), Money.brl(10000), Money.brl(7001), "payment amount"),
        "refunds would total more than the payment amount; remaining 7000");
  }

  @Test
  void nothingLeftExceedsEvenWithoutAnAmount() {
    assertExceeds(
        () ->
            RefundReservation.reserve(
                List.of(refund(10000)), Money.brl(10000), null, "paid amount"),
        "remaining 0");
  }

  @Test
  void aZeroRequestExceeds() {
    assertExceeds(
        () -> RefundReservation.reserve(List.of(), Money.brl(10000), Money.brl(0), "paid amount"),
        "remaining 10000");
  }
}
