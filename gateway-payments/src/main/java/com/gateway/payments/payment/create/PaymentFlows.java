package com.gateway.payments.payment.create;

import com.gateway.kernel.payment.PaymentMethod;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One flow per method, indexed once at construction and complete or nothing: a method with no flow,
 * or with two, fails the startup instead of becoming a 500 the first time a merchant asks for it.
 */
public class PaymentFlows {
  private final Map<PaymentMethod, PaymentFlow> byMethod;

  public PaymentFlows(List<PaymentFlow> flows) {
    Map<PaymentMethod, PaymentFlow> indexed = new EnumMap<>(PaymentMethod.class);

    for (PaymentFlow flow : flows) {
      PaymentFlow previous = indexed.put(flow.method(), flow);
      if (previous != null) {
        throw new IllegalStateException("two payment flows for " + flow.method());
      }
    }

    for (PaymentMethod method : PaymentMethod.values()) {
      if (!indexed.containsKey(method)) {
        throw new IllegalStateException("no payment flow for " + method);
      }
    }

    this.byMethod = indexed;
  }

  public PaymentFlow forMethod(PaymentMethod method) {
    return byMethod.get(method);
  }
}
