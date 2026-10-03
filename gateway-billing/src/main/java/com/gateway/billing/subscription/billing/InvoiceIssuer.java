package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.AttemptRequest;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.Subscription;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardDeclinedException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One attempt for one invoice, by the subscription's method (spec §6 step 2). Outside any
 * transaction: the bank is called here. A card decline is an outcome, not an exception, so the
 * caller can book PAST_DUE in its own transaction.
 */
public class InvoiceIssuer {
  /**
   * One day, the plan's figure: the invoice lives for weeks, and a copy-paste valid that long would
   * outlive the dunning retries that issue a fresh one.
   */
  private static final long MAX_PIX_SECONDS = Duration.ofDays(1).toSeconds();

  private static final long MIN_PIX_SECONDS = 60;

  /**
   * A boleto stays payable a week past the invoice's own expiry: the default dunning schedule's
   * last retry is day 7, and a payer who pays late should settle this one, not race a fresh
   * attempt.
   */
  private static final long BOLETO_GRACE_DAYS = 7;

  private final OrderAttemptService attempts;
  private final PaymentQueries payments;

  public InvoiceIssuer(OrderAttemptService attempts, PaymentQueries payments) {
    this.attempts = attempts;
    this.payments = payments;
  }

  public IssuedInvoice issue(Subscription subscription, Order invoice, Instant now, Instant until) {
    AttemptRequest request = requestFor(subscription, invoice, now, until);

    try {
      Payment payment = attempts.attempt(invoice, request, EventSource.SYSTEM);

      return new IssuedInvoice(payment, payment.status() == PaymentStatus.COMPLETED, null);
    } catch (CardDeclinedException declined) {
      // The FAILED row committed before the throw; dunning and invoice.created both name it.
      Payment payment = payments.get(invoice.merchantId(), declined.paymentId());

      return new IssuedInvoice(payment, false, declined.declineCode());
    }
  }

  /**
   * A FAILED card attempt that the acquirer answered: an outcome to book, not an error to retry.
   */
  public static boolean isDecline(Payment payment) {
    return payment.status() == PaymentStatus.FAILED
        && payment.card() != null
        && payment.card().declineCode() != null;
  }

  /** The outcome of an attempt declined on an earlier run, rebuilt from its committed row. */
  public static IssuedInvoice declinedBy(Payment payment) {
    return new IssuedInvoice(payment, false, payment.card().declineCode());
  }

  /**
   * Capped at a day, floored at a minute: a run resumed after {@code until} (a job retried past the
   * invoice's expiry) would otherwise send a zero or negative expiry, which the provider refuses.
   */
  static int pixExpirySeconds(Instant now, Instant until) {
    long left = Duration.between(now, until).toSeconds();

    return (int) Math.max(MIN_PIX_SECONDS, Math.min(MAX_PIX_SECONDS, left));
  }

  /** The dispatch point: the one switch over the subscription's method. */
  private static AttemptRequest requestFor(
      Subscription subscription, Order invoice, Instant now, Instant until) {
    Duration left = Duration.between(now, until);

    return switch (subscription.method()) {
      case CARD -> new AttemptRequest.RecurringCardAttempt(subscription.cardId(), 1);
      case PIX -> new AttemptRequest.PixAttempt(pixExpirySeconds(now, until));
      case BOLECODE -> {
        LocalDate due =
            invoice.periodEnd() == null ? BillingCalendar.today(until) : invoice.periodEnd();
        int limitDays = (int) Math.max(1, left.toDays() + BOLETO_GRACE_DAYS);
        yield new AttemptRequest.BolecodeAttempt(due, limitDays);
      }
    };
  }
}
