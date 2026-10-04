package com.gateway.billing.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class PlanFactoryTest {
  @Test
  void defaultsIntervalCountAndTrial() {
    Plan plan =
        PlanFactory.fromRequest(
            MerchantId.next(),
            "Pro",
            Money.brl(9900),
            PlanInterval.MONTH,
            null,
            null,
            Clock.systemUTC());

    assertThat(plan.intervalCount()).isEqualTo(1);
    assertThat(plan.trialDays()).isEqualTo(0);
    assertThat(plan.active()).isTrue();
  }

  @Test
  void refusesMoreThanTwelveIntervals() {
    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    MerchantId.next(),
                    "Pro",
                    Money.brl(9900),
                    PlanInterval.MONTH,
                    13,
                    null,
                    Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("interval_count");
  }

  @Test
  void refusesInvalidNameAmountIntervalAndTrial() {
    MerchantId merchant = MerchantId.next();

    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    merchant,
                    " ",
                    Money.brl(9900),
                    PlanInterval.MONTH,
                    null,
                    null,
                    Clock.systemUTC()))
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    merchant,
                    "x".repeat(81),
                    Money.brl(9900),
                    PlanInterval.MONTH,
                    null,
                    null,
                    Clock.systemUTC()))
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    merchant,
                    "Pro",
                    Money.brl(0),
                    PlanInterval.MONTH,
                    null,
                    null,
                    Clock.systemUTC()))
        .hasMessageContaining("amount");
    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    merchant, "Pro", Money.brl(9900), null, null, null, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("interval");
    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    merchant, "Pro", Money.brl(9900), PlanInterval.DAY, 0, null, Clock.systemUTC()))
        .hasMessageContaining("interval_count");
    assertThatThrownBy(
            () ->
                PlanFactory.fromRequest(
                    merchant,
                    "Pro",
                    Money.brl(9900),
                    PlanInterval.DAY,
                    null,
                    366,
                    Clock.systemUTC()))
        .hasMessageContaining("trial_days");
  }
}
