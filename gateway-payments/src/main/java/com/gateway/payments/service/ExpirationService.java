package com.gateway.payments.service;

import com.gateway.kernel.provider.Charge;
import com.gateway.kernel.provider.ChargeStatus;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.domain.EventSource;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.repository.PaymentRepository;
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

  /** Returns whether the payment changed state. A payment no longer PENDING (or not yet due) is a no-op. */
  public boolean expireOne(String paymentId, Instant now) {
    Payment p = payments.findById(paymentId).orElse(null);
    if (p == null || p.status() != PaymentStatus.PENDING || p.expiresAt().plus(props.expirationGrace()).isAfter(now)) {
      return false;
    }
    ProviderGateway.Resolved r = providers.resolve(p.merchantId(), p.environment(), p.provider());
    Optional<Charge> atBank = providers.call(p.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), p.id()));
    if (atBank.isPresent() && atBank.get().status() == ChargeStatus.COMPLETED && atBank.get().firstPix().isPresent()) {
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
    return Boolean.TRUE.equals(
        tx.execute(s -> {
          Payment loaded = payments.findById(paymentId).orElseThrow();
          if (loaded.status() != PaymentStatus.PENDING) {
            return false; // the webhook got there while we were asking the bank
          }
          Payment saved = payments.save(loaded, List.of(loaded.markExpired(EventSource.EXPIRATION_JOB)));
          events.emit(saved.merchantId(), "payment.expired", saved);
          return true;
        }));
  }
}
