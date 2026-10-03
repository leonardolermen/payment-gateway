package com.gateway.billing.subscription;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.plan.Plan;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Card ownership ({@code CARD_NOT_OWNED_BY_CUSTOMER}) is not checked here: it needs the saved card,
 * and {@link SubscriptionService} is the one holding {@code SavedCards}.
 */
public final class SubscriptionFactory {
  private SubscriptionFactory() {}

  public static Subscription fromRequest(
      MerchantId merchantId,
      ProviderEnvironment environment,
      Customer customer,
      Plan plan,
      PaymentMethod method,
      String cardId,
      LocalDate startDay,
      Clock clock) {
    if (!plan.active()) {
      throw new DomainException("PLAN_INACTIVE", "plan_id " + plan.id() + " is inactive");
    }
    requireMethodData(customer, method, cardId);

    LocalDate firstBillingDay = startDay.plusDays(plan.trialDays());

    return new Subscription(
        Ulid.next(),
        merchantId,
        environment,
        customer.id(),
        plan.id(),
        method,
        cardId,
        firstBillingDay.getDayOfMonth(),
        firstBillingDay,
        clock.instant());
  }

  /** Shared with a method change: the method a subscription moves to obeys the same rules. */
  static void requireMethodData(Customer customer, PaymentMethod method, String cardId) {
    if (method == PaymentMethod.CARD && cardId == null) {
      throw new DomainException("CARD_REQUIRED", "card_id is required for method CARD");
    }
    if (method != PaymentMethod.CARD && cardId != null) {
      throw new IllegalArgumentException("card_id applies to method CARD only");
    }
    if (method == PaymentMethod.BOLECODE && !customer.hasAddress()) {
      throw new DomainException(
          "CUSTOMER_ADDRESS_REQUIRED", "customer.address is required for BOLECODE");
    }
  }
}
