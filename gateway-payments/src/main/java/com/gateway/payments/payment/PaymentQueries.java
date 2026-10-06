package com.gateway.payments.payment;

import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Reading a merchant's payments: no bank call and no write, so no transaction. */
public class PaymentQueries {
  private final PaymentRepository payments;

  public PaymentQueries(PaymentRepository payments) {
    this.payments = payments;
  }

  public Payment get(MerchantId merchantId, String id) {
    return payments
        .findByMerchantAndId(merchantId, id)
        .orElseThrow(() -> new NotFoundException("payment", id));
  }

  public List<Payment> list(MerchantId merchantId, int limit, String cursor) {
    return payments.listByMerchant(merchantId, limit, cursor);
  }

  /**
   * The merchant's own order reference, newest first. What a client checks after a 409 IN_PROGRESS
   * on a create: whether the interrupted request left a payment behind before retrying with a new
   * key.
   */
  public List<Payment> listByReference(MerchantId merchantId, String reference, int limit) {
    return payments.listByMerchantAndReference(merchantId, reference, limit);
  }

  /** The attempt holding a billing order's one active slot, if any. Unscoped: billing's id. */
  public Optional<Payment> activeAttempt(String orderId) {
    return payments.findActiveByOrder(orderId);
  }

  public List<Payment> listByOrder(MerchantId merchantId, String orderId) {
    return payments.listByMerchantAndOrder(merchantId, orderId);
  }

  public List<Payment> listByOrders(MerchantId merchantId, Collection<String> orderIds) {
    return payments.listByMerchantAndOrders(merchantId, orderIds);
  }

  public List<PaymentEvent> events(MerchantId merchantId, String id) {
    return payments.events(get(merchantId, id).id());
  }
}
