package com.gateway.billing.order;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Instant;
import java.time.LocalDate;

/** A class, not a record: it mutates through {@link OrderTransitions} like {@code Payment}. */
public final class Order {
  private final String id;
  private final MerchantId merchantId;
  private final ProviderEnvironment environment;
  private final String customerId;
  private final OrderPayer payer;
  private final Money amount;
  private final String reference;
  private final String description;
  private final Instant expiresAt;
  private final String subscriptionId;
  private final Integer invoiceNumber;
  private final LocalDate periodStart;
  private final LocalDate periodEnd;
  private final Instant createdAt;

  private OrderStatus status;
  private String paidPaymentId;
  private Instant paidAt;
  private long version;
  private Instant updatedAt;

  Order(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String customerId,
      OrderPayer payer,
      Money amount,
      String reference,
      String description,
      Instant expiresAt,
      String subscriptionId,
      Integer invoiceNumber,
      LocalDate periodStart,
      LocalDate periodEnd,
      Instant createdAt) {
    this.id = id;
    this.merchantId = merchantId;
    this.environment = environment;
    this.customerId = customerId;
    this.payer = payer;
    this.amount = amount;
    this.reference = reference;
    this.description = description;
    this.expiresAt = expiresAt;
    this.subscriptionId = subscriptionId;
    this.invoiceNumber = invoiceNumber;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.createdAt = createdAt;
    this.status = OrderStatus.OPEN;
    this.version = 1;
    this.updatedAt = createdAt;
  }

  /** The bank said a payment of this order settled. Only payments decide that; this mirrors it. */
  public void markPaid(String paymentId, Instant at) {
    transition(OrderStatus.PAID, at);
    this.paidPaymentId = paymentId;
    this.paidAt = at;
  }

  public void markCanceled(Instant at) {
    transition(OrderStatus.CANCELED, at);
  }

  public void markExpired(Instant at) {
    transition(OrderStatus.EXPIRED, at);
  }

  private void transition(OrderStatus to, Instant at) {
    if (!OrderTransitions.allowed(status, to)) {
      throw new IllegalStateException("order " + id + " is " + status + ", cannot become " + to);
    }

    this.status = to;
    this.version++;
    this.updatedAt = at;
  }

  public boolean isOpen() {
    return status == OrderStatus.OPEN;
  }

  public boolean isInvoice() {
    return subscriptionId != null;
  }

  public String id() {
    return id;
  }

  public MerchantId merchantId() {
    return merchantId;
  }

  public ProviderEnvironment environment() {
    return environment;
  }

  public String customerId() {
    return customerId;
  }

  public OrderPayer payer() {
    return payer;
  }

  public Money amount() {
    return amount;
  }

  public String reference() {
    return reference;
  }

  public String description() {
    return description;
  }

  public OrderStatus status() {
    return status;
  }

  public String paidPaymentId() {
    return paidPaymentId;
  }

  public Instant paidAt() {
    return paidAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public String subscriptionId() {
    return subscriptionId;
  }

  public Integer invoiceNumber() {
    return invoiceNumber;
  }

  public LocalDate periodStart() {
    return periodStart;
  }

  public LocalDate periodEnd() {
    return periodEnd;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public static Order rehydrate(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String customerId,
      OrderPayer payer,
      Money amount,
      String reference,
      String description,
      OrderStatus status,
      String paidPaymentId,
      Instant paidAt,
      Instant expiresAt,
      String subscriptionId,
      Integer invoiceNumber,
      LocalDate periodStart,
      LocalDate periodEnd,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    Order order =
        new Order(
            id,
            merchantId,
            environment,
            customerId,
            payer,
            amount,
            reference,
            description,
            expiresAt,
            subscriptionId,
            invoiceNumber,
            periodStart,
            periodEnd,
            createdAt);

    order.status = status;
    order.paidPaymentId = paidPaymentId;
    order.paidAt = paidAt;
    order.version = version;
    order.updatedAt = updatedAt;

    return order;
  }
}
