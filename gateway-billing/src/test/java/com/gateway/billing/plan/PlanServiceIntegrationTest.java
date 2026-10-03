package com.gateway.billing.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PlanServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired PlanService plans;

  Plan pro() {
    return plans.create(
        PlanFactory.fromRequest(
            merchant, "Pro", Money.brl(9900), PlanInterval.MONTH, null, 7, clock));
  }

  @Test
  void createsAndReadsBack() {
    Plan created = pro();

    Plan found = plans.get(merchant, created.id());

    assertThat(found.name()).isEqualTo("Pro");
    assertThat(found.amount()).isEqualTo(Money.brl(9900));
    assertThat(found.interval()).isEqualTo(PlanInterval.MONTH);
    assertThat(found.intervalCount()).isEqualTo(1);
    assertThat(found.trialDays()).isEqualTo(7);
    assertThat(found.active()).isTrue();
    assertThat(found.version()).isEqualTo(1);
    assertThat(plans.list(merchant, true)).extracting(Plan::id).containsExactly(created.id());
  }

  @Test
  void renameBumpsVersion() {
    Plan created = pro();

    Plan renamed = plans.rename(merchant, created.id(), "Pro Plus");

    assertThat(renamed.version()).isEqualTo(2);
    assertThat(plans.get(merchant, created.id()).name()).isEqualTo("Pro Plus");
  }

  @Test
  void deactivatedPlanLeavesActiveListButStaysReadable() {
    Plan created = pro();

    plans.setActive(merchant, created.id(), false);

    assertThat(plans.list(merchant, true)).isEmpty();
    assertThat(plans.list(merchant, false)).extracting(Plan::id).containsExactly(created.id());
    assertThat(plans.list(merchant, null)).extracting(Plan::id).containsExactly(created.id());
    assertThat(plans.get(merchant, created.id()).active()).isFalse();
  }

  @Test
  void unknownPlanIsNotFound() {
    assertThatThrownBy(() -> plans.get(merchant, "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        .isInstanceOf(NotFoundException.class);
  }
}
