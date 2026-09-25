package com.gateway.payments.payment;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.provider.ProviderGateway;

import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires PENDING charges — but asks the bank first. The payer may have paid in the last second
 * and the webhook may be late; expiring on our clock alone would send the merchant
 * {@code payment.expired} for money that arrived.
 */
public class ExpirationService {
  private static final Logger log = LoggerFactory.getLogger(ExpirationService.class);
  private static final int BATCH = 100;

  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final PaymentEvents events;
  private final PaymentsProperties props;
  private final TransactionTemplate tx;

  public ExpirationService(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PaymentEvents events,
      PaymentsProperties props,
      TransactionTemplate tx) {
    this.payments = payments;
    this.providers = providers;
    this.paymentService = paymentService;
    this.events = events;
    this.props = props;
    this.tx = tx;
  }

  /** Sweep for PENDING charges past expiry + grace; the per-payment job is the normal path, this is the net. */
  public int expireDue(Instant now) {
    List<Payment> due = payments.findPendingOlderThan(now.minus(props.expirationGrace()), BATCH);
    int changed = 0;
    for (Payment p : due) {
      try {
        if (expireOne(p.id(), now)) changed++;
      } catch (RuntimeException e) {
        // One bank hiccup must not stop the sweep for every other merchant's charges.
        log.warn("could not expire payment {}", p.id(), e);
      }
    }
    return changed;
  }

  /**
   * CREATED older than {@code stuckCreatedAfter} means the createCharge call never finished (the
   * process died between the insert and the bank's answer). The txid is ours, so the bank can say
   * what became of it: ACTIVE is adopted as PENDING (by SYSTEM), COMPLETED is adopted and then
   * settled, absent is FAILED. Without this, a charge the bank accepted stays CREATED forever and
   * its payment would never reach the merchant.
   */
  public int sweepStuckCreated(Instant now) {
    int changed = 0;
    for (Payment p : payments.findByStatusCreatedBefore(PaymentStatus.CREATED, now.minus(props.stuckCreatedAfter()), BATCH)) {
      try {
        ProviderGateway.Resolved r = providers.resolve(p.merchantId(), p.environment(), p.provider());
        if (p.method() == PaymentMethod.BOLECODE) {
          // Same idea as Pix, with the query: the number is ours, so the bank can say whether the
          // issue landed. Empty after stuckCreatedAfter (the bank's 202 long past) is FAILED.
          BoletoProvider boleto = r.boleto().orElseThrow();
          String nn = p.boleto().nossoNumero();
          Optional<BoletoStatus> atBank = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
          if (atBank.isEmpty()) {
            paymentService.markFailed(p.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
          } else {
            paymentService.adoptBolecodeFromStatus(p.id(), r, atBank.get(), EventSource.SYSTEM);
            if (atBank.get().paid()) {
              paymentService.settleBoleto(p.merchantId(), p.id(), atBank.get(), EventSource.RECONCILIATION);
            }
          }
          changed++;
          continue;
        }
        Optional<Charge> atBank = providers.call(p.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), p.id()));
        if (atBank.isEmpty()) {
          paymentService.markFailed(p.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
        } else {
          int fallback = (int) java.time.Duration.between(p.createdAt(), p.expiresAt()).toSeconds();
          paymentService.adoptPending(p.id(), atBank.get(), fallback, EventSource.SYSTEM);
          if (atBank.get().status() == ChargeStatus.COMPLETED && atBank.get().firstPix().isPresent()) {
            paymentService.settle(p.merchantId(), p.id(), atBank.get().firstPix().get(), EventSource.RECONCILIATION);
          }
        }
        changed++;
      } catch (RuntimeException e) {
        log.warn("could not resolve stuck CREATED payment {}", p.id(), e);
      }
    }
    return changed;
  }

  /** Returns whether the payment changed state. A payment no longer PENDING (or not yet due) is a no-op. */
  public boolean expireOne(String paymentId, Instant now) {
    Payment p = payments.findById(paymentId).orElse(null);
    if (p == null || p.status() != PaymentStatus.PENDING || p.expiresAt().plus(props.expirationGrace()).isAfter(now)) {
      return false;
    }
    ProviderGateway.Resolved r = providers.resolve(p.merchantId(), p.environment(), p.provider());
    if (p.method() == PaymentMethod.BOLECODE) {
      return expireBolecode(p, r);
    }
    Optional<Charge> atBank = providers.call(p.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), p.id()));
    if (atBank.isPresent() && atBank.get().status() == ChargeStatus.COMPLETED) {
      if (atBank.get().firstPix().isEmpty()) {
        // Paid, but without the pix[] that says by whom and how much: we cannot complete it, and
        // expiring a charge the bank calls paid would be wrong. Left PENDING for the webhook or the
        // next reconciliation to bring the details.
        log.warn("bank reports payment {} COMPLETED without pix[]; leaving it PENDING", p.id());
        return false;
      }
      paymentService.settle(p.merchantId(), p.id(), atBank.get().firstPix().get(), EventSource.RECONCILIATION);
      return true;
    }
    if (atBank.isPresent() && atBank.get().status() == ChargeStatus.ACTIVE) {
      try {
        // Best effort: the bank expires the QR on its own clock anyway; removing it just closes
        // the window between our expiry and the bank's.
        providers.run(p.id(), "cancelCharge", r, x -> x.provider().cancelCharge(x.credentials(), p.id()));
      } catch (ProviderException e) {
        if (e.code() != ProviderException.Code.INVALID && e.code() != ProviderException.Code.NOT_FOUND) {
          log.info("could not remove expired charge {} at the bank: {}", p.id(), e.getMessage());
        }
      }
    }
    return markExpired(paymentId);
  }

  /**
   * A Bolecode expires on its payment limit date (spec 2026-09-25 §7, §10): after it the bank refuses
   * both the barcode and the QR, so no baixa is sent — it would only add a call that can fail. The
   * query still runs first: a payment made on the last day is credited on the next business day.
   */
  private boolean expireBolecode(Payment p, ProviderGateway.Resolved r) {
    BoletoProvider boleto = r.boleto().orElseThrow();
    String nn = p.boleto().nossoNumero();
    Optional<BoletoStatus> atBank = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
    if (atBank.isPresent() && atBank.get().paid()) {
      return paymentService.settleBoleto(p.merchantId(), p.id(), atBank.get(), EventSource.RECONCILIATION) == PaymentService.Settlement.COMPLETED;
    }
    if (atBank.isPresent() && atBank.get().situation() == BoletoSituation.AWAITING_CREDIT) {
      log.info("boleto {} of payment {} awaiting credit at the bank; not expiring yet", nn, p.id());
      return false;
    }
    return markExpired(p.id());
  }

  private boolean markExpired(String paymentId) {
    return Boolean.TRUE.equals(
        tx.execute(s -> {
          Payment loaded = payments.findById(paymentId).orElseThrow();
          if (loaded.status() != PaymentStatus.PENDING) {
            return false; // a webhook or a poll got there while we were asking the bank
          }
          Payment saved = payments.save(loaded, List.of(loaded.markExpired(EventSource.EXPIRATION_JOB)));
          events.emit(saved.merchantId(), "payment.expired", saved);
          return true;
        }));
  }
}
