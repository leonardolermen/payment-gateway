package com.gateway.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanFactory;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SubscriptionFactoryTest {
  private static final MerchantId MERCHANT = MerchantId.next();
  private static final Clock CLOCK = Clock.systemUTC();
  private static final LocalDate START = LocalDate.of(2026, 10, 2);

  private static Customer customer(CustomerAddress.Raw address) {
    return CustomerFactory.fromRequest(
        MERCHANT, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null, address, CLOCK);
  }

  private static Plan plan(Integer trialDays) {
    return PlanFactory.fromRequest(
        MERCHANT, "Pro", Money.brl(9900), PlanInterval.MONTH, null, trialDays, CLOCK);
  }

  private static Subscription create(
      Customer customer, Plan plan, PaymentMethod method, String cardId) {
    return SubscriptionFactory.fromRequest(
        MERCHANT, ProviderEnvironment.TEST, customer, plan, method, cardId, START, CLOCK);
  }

  private static String codeOf(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }

    throw new AssertionError("expected a DomainException");
  }

  @Test
  void startsActiveWithNoPeriodAndAnchorsOnTheStartDay() {
    Subscription subscription = create(customer(null), plan(null), PaymentMethod.PIX, null);

    assertThat(subscription.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(subscription.currentPeriod()).isNull();
    assertThat(subscription.nextBillingAt()).isNull();
    assertThat(subscription.lastInvoiceNumber()).isZero();
    assertThat(subscription.cancelAtPeriodEnd()).isFalse();
    assertThat(subscription.startDay()).isEqualTo(START);
    assertThat(subscription.anchorDay()).isEqualTo(2);
    assertThat(subscription.version()).isEqualTo(1L);
  }

  @Test
  void aTrialMovesTheFirstBillingDayAndTheAnchor() {
    Subscription subscription = create(customer(null), plan(30), PaymentMethod.PIX, null);

    assertThat(subscription.startDay()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(subscription.anchorDay()).isEqualTo(1);
  }

  @Test
  void anInactivePlanIsRefused() {
    Plan inactive = plan(null).deactivate(CLOCK.instant());

    assertThat(codeOf(() -> create(customer(null), inactive, PaymentMethod.PIX, null)))
        .isEqualTo("PLAN_INACTIVE");
  }

  @Test
  void cardWithoutACardIdIsRefused() {
    assertThat(codeOf(() -> create(customer(null), plan(null), PaymentMethod.CARD, null)))
        .isEqualTo("CARD_REQUIRED");
  }

  @Test
  void bolecodeWithoutAnAddressIsRefused() {
    assertThat(codeOf(() -> create(customer(null), plan(null), PaymentMethod.BOLECODE, null)))
        .isEqualTo("CUSTOMER_ADDRESS_REQUIRED");
  }

  @Test
  void bolecodeWithAnAddressIsAccepted() {
    CustomerAddress.Raw address =
        new CustomerAddress.Raw("Rua A 1", "Centro", "Sao Paulo", "SP", "01310100");

    Subscription subscription = create(customer(address), plan(null), PaymentMethod.BOLECODE, null);

    assertThat(subscription.method()).isEqualTo(PaymentMethod.BOLECODE);
  }

  @Test
  void aCardIdOnAnotherMethodIsAProgrammingError() {
    assertThatThrownBy(() -> create(customer(null), plan(null), PaymentMethod.PIX, "card-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
