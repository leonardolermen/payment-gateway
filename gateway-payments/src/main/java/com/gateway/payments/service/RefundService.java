package com.gateway.payments.service;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.RefundRequest;
import com.gateway.kernel.provider.RefundResult;
import com.gateway.kernel.provider.RefundStatus;
import com.gateway.payments.domain.EventSource;
import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.domain.Refund;
import com.gateway.payments.domain.RefundState;
import com.gateway.payments.repository.JobRepository;
import com.gateway.payments.repository.PaymentRepository;
import com.gateway.payments.repository.RefundRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refunds are asynchronous at Itau (docs/providers/itau/NOTES.md): the PUT answers
 * {@code EM_PROCESSAMENTO} and the outcome arrives later, by webhook or by polling. So a request
 * ends at PROCESSING with a {@code POLL_REFUND} job behind it, and only
 * {@link #applyProviderUpdate} moves money on the payment — a refund the bank has not settled must
 * not show up in {@code refunded_amount}.
 *
 * <p>Both the reserve (sum check + insert) and the settlement (read-modify-write of
 * {@code refunded_amount}) hold the payment row lock ({@link PaymentRepository#findByIdForUpdate}):
 * two concurrent requests that each fit alone must not both pass the sum check.
 */
public class RefundService {
  private static final Logger log = LoggerFactory.getLogger(RefundService.class);
  /** The bank's own window (NOTES.md); asking after it only buys a 4xx from the bank. */
  static final Duration WINDOW = Duration.ofDays(90);
  /** Same period the polling keeps afterwards (JobRunner). */
  static final Duration POLL_EVERY = Duration.ofMinutes(5);

  private final RefundRepository refunds;
  private final PaymentRepository payments;
  private final JobRepository jobs;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final PaymentService paymentService;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RefundService(
      RefundRepository refunds,
      PaymentRepository payments,
      JobRepository jobs,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentService paymentService,
      TransactionTemplate tx,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.jobs = jobs;
    this.providers = providers;
    this.events = events;
    this.paymentService = paymentService;
    this.tx = tx;
    this.clock = clock;
  }

  /** {@code amountOrNull == null} refunds whatever is not yet refunded or in flight. */
  public Refund request(MerchantId merchantId, String paymentId, Money amountOrNull) {
    Payment payment = payments.findByMerchantAndId(merchantId, paymentId).orElseThrow(() -> new NotFoundException("payment", paymentId));
    if (amountOrNull != null && amountOrNull.isZero()) {
      throw new DomainException("INVALID_AMOUNT", "a refund must be greater than zero");
    }
    ProviderGateway.Resolved r = providers.resolve(merchantId, payment.environment(), payment.provider());

    Refund refund =
        tx.execute(s -> {
          Payment locked = payments.findByIdForUpdate(paymentId).orElseThrow();
          if (locked.status() != PaymentStatus.COMPLETED) {
            throw new DomainException("INVALID_STATE", "only a completed payment can be refunded, this one is " + locked.status());
          }
          if (locked.paidAt() != null && clock.instant().isAfter(locked.paidAt().plus(WINDOW))) {
            throw new DomainException("REFUND_WINDOW_CLOSED", "the bank accepts refunds up to 90 days after payment");
          }
          // Reserved = everything not FAILED: a PROCESSING or UNKNOWN refund is money the bank may
          // still send back, and counting only COMPLETED ones would let two quick requests exceed
          // the original.
          long reserved =
              refunds.findByPayment(paymentId).stream().filter(x -> x.state() != RefundState.FAILED).mapToLong(x -> x.amount().cents()).sum();
          long remaining = locked.amount().cents() - reserved;
          Money amount = amountOrNull == null ? new Money(Math.max(remaining, 0), locked.amount().currency()) : amountOrNull;
          if (amount.isZero() || amount.cents() > remaining) {
            throw new DomainException("REFUND_EXCEEDS_AMOUNT", "refunds would total more than the payment amount; remaining " + Math.max(remaining, 0));
          }
          return refunds.save(Refund.request(paymentId, merchantId, amount, clock));
        });

    RefundResult result;
    try {
      result =
          providers.call(
              paymentId,
              "requestRefund",
              r,
              x -> x.provider().requestRefund(x.credentials(), new RefundRequest(payment.pix().endToEndId(), refund.id(), refund.amount())));
    } catch (ProviderException e) {
      if (!definitelyRefused(e.code())) {
        // The refund id is ours, so the PUT is safe to have landed: polling asks the bank for it by
        // that id and settles whichever way the bank says. A 503/504 from a gateway in front of the
        // bank says no more about that than a timeout does; failing it here freed the reserve while
        // the money could still leave, and a second refund on top would pay the payer twice.
        // A refund the bank never saw is FAILED by polling after refundNotFoundGrace.
        log.warn("refund {} PUT ended in {}; left PROCESSING for polling to decide", refund.id(), e.code());
        result = new RefundResult(refund.id(), RefundStatus.PROCESSING, refund.amount(), null, clock.instant(), null);
      } else {
        String code = "PROVIDER_DECLINED";
        tx.executeWithoutResult(s -> {
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markFailed(ProviderErrors.message(code));
          events.emitRefund(merchantId, "refund.failed", refunds.save(loaded), payment);
        });
        throw ProviderErrors.toDomain(code, e, log, "requestRefund", refund.id());
      }
    }

    Refund processing =
        tx.execute(s -> {
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markProcessing();
          Refund saved = refunds.save(loaded);
          if (!jobs.enqueue(Job.pollRefund(saved.id(), clock.instant().plus(POLL_EVERY), clock))) {
            log.debug("poll job for refund {} was already queued", saved.id());
          }
          events.emitRefund(merchantId, "refund.requested", saved, payment);
          return saved;
        });
    if (result.status() != RefundStatus.PROCESSING) {
      applyProviderUpdate(result);
      return refunds.findById(refund.id()).orElseThrow();
    }
    return processing;
  }

  /**
   * The only answers that prove the bank did not take the refund: it refused the request (400/422)
   * or does not know the Pix it is against (404). Anything else may have landed.
   */
  private static boolean definitelyRefused(ProviderException.Code code) {
    return code == ProviderException.Code.INVALID || code == ProviderException.Code.DECLINED || code == ProviderException.Code.NOT_FOUND;
  }

  public Refund get(MerchantId merchantId, String refundId) {
    return refunds.findById(refundId).filter(r -> r.merchantId().equals(merchantId)).orElseThrow(() -> new NotFoundException("refund", refundId));
  }

  public List<Refund> list(MerchantId merchantId, String paymentId) {
    payments.findByMerchantAndId(merchantId, paymentId).orElseThrow(() -> new NotFoundException("payment", paymentId));
    return refunds.findByPayment(paymentId);
  }

  /**
   * The bank's word on a refund, from polling or from a webhook confirmed by
   * {@link #confirmFromWebhook}; never from a webhook body alone. Idempotent: a refund already
   * COMPLETED or FAILED ignores later notifications, so a webhook and a poll racing to deliver the
   * same settlement cannot count the money twice. UNKNOWN still accepts COMPLETED/FAILED: it is
   * "we stopped asking", not "the bank decided". Settlement is recorded as a payment event
   * ({@code refund_completed}/{@code refund_failed}) so it bumps the payment's version.
   */
  public void applyProviderUpdate(RefundResult result) {
    tx.executeWithoutResult(s -> {
      Refund probe = refunds.findById(result.refundId()).orElse(null);
      if (probe == null) {
        log.warn("provider update for unknown refund {}", result.refundId());
        return;
      }
      // Lock the payment first, then re-read the refund under that lock: the terminal check below
      // is what keeps a racing webhook and poll from both applying.
      Payment payment = payments.findByIdForUpdate(probe.paymentId()).orElseThrow();
      Refund refund = refunds.findById(result.refundId()).orElseThrow();
      if (refund.state() == RefundState.COMPLETED || refund.state() == RefundState.FAILED) {
        return;
      }
      EventSource by = EventSource.RECONCILIATION;
      switch (result.status()) {
        case PROCESSING -> {
          if (refund.state() == RefundState.REQUESTED) {
            refund.markProcessing();
            refunds.save(refund);
          }
        }
        case COMPLETED -> {
          refund.markCompleted(result.settledAt() == null ? clock.instant() : result.settledAt());
          Refund saved = refunds.save(refund);
          Payment updated = payments.save(payment, List.of(payment.recordRefund(refund.id(), refund.amount(), true, by)));
          events.emitRefund(refund.merchantId(), "refund.completed", saved, updated);
        }
        case FAILED -> {
          refund.markFailed(result.reason());
          Refund saved = refunds.save(refund);
          Payment updated = payments.save(payment, List.of(payment.recordRefund(refund.id(), refund.amount(), false, by)));
          events.emitRefund(refund.merchantId(), "refund.failed", saved, updated);
        }
      }
    });
  }

  /**
   * Polling ran out of budget ({@code refundPollMaxAttempts}, 24 h) with the bank still saying
   * EM_PROCESSAMENTO. This used to mark the refund FAILED, which freed its amount: the merchant
   * could refund again while the first devolucao was still able to settle, and the payer got paid
   * back twice. Now: UNKNOWN (still reserved), {@code refund.unknown} to the merchant and a
   * {@code REFUND_UNKNOWN} divergence, in one transaction so neither exists without the other.
   */
  public void giveUp(String refundId) {
    tx.executeWithoutResult(s -> {
      Refund probe = refunds.findById(refundId).orElse(null);
      if (probe == null) {
        return;
      }
      Payment payment = payments.findByIdForUpdate(probe.paymentId()).orElseThrow();
      Refund refund = refunds.findById(refundId).orElseThrow();
      if (refund.state() != RefundState.REQUESTED && refund.state() != RefundState.PROCESSING) {
        return;
      }
      refund.markUnknown("refund status unknown after the polling budget; check the bank");
      Refund saved = refunds.save(refund);
      events.emitRefund(refund.merchantId(), "refund.unknown", saved, payment);
      paymentService.openDivergence(payment, "REFUND_UNKNOWN", "refund " + refundId + " still processing at the bank after the polling budget");
    });
  }

  /**
   * A refund status carried by the bank's webhook. Like a received Pix, the body is a hint: it is
   * applied only when the refund belongs to the merchant whose webhook URL was called, hangs off
   * that payment's own endToEndId, and the bank's {@code GET /devolucao} agrees. What gets applied
   * is the bank's answer, not the body's. Returns whether the update was ours; a mismatch opens a
   * divergence on the refund's payment. The bank unreachable propagates so the inbox job retries.
   */
  public boolean confirmFromWebhook(MerchantId merchantId, String endToEndId, RefundResult hinted) {
    Refund refund = refunds.findById(hinted.refundId()).orElse(null);
    if (refund == null) {
      log.warn("webhook refund update for unknown refund {}", hinted.refundId());
      return false;
    }
    Payment payment = payments.findById(refund.paymentId()).orElseThrow();
    String paymentE2e = payment.pix() == null ? null : payment.pix().endToEndId();
    if (!refund.merchantId().equals(merchantId) || endToEndId == null || !endToEndId.equals(paymentE2e)) {
      log.warn("webhook refund update for refund {} does not match its merchant or payment; ignored", refund.id());
      tx.executeWithoutResult(
          s -> paymentService.openDivergence(
              payment,
              "UNCONFIRMED_REFUND_WEBHOOK",
              "webhook for merchant " + merchantId.value() + ", e2eid " + endToEndId + " named refund " + refund.id() + " of another merchant or payment"));
      return false;
    }
    ProviderGateway.Resolved r = providers.resolve(payment.merchantId(), payment.environment(), payment.provider());
    Optional<RefundResult> atBank = providers.call(payment.id(), "findRefund", r, x -> x.provider().findRefund(x.credentials(), paymentE2e, refund.id()));
    if (atBank.isEmpty()) {
      log.warn("webhook refund update for {} not known at the bank; polling decides", refund.id());
      return true;
    }
    applyProviderUpdate(atBank.get());
    return true;
  }
}
