package com.gateway.payments.payment;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expires PENDING charges — but asks the bank first. The payer may have paid in the last second and
 * the webhook may be late; expiring on our clock alone would send the merchant {@code
 * payment.expired} for money that arrived.
 */
public class PaymentExpiration {
  private static final Logger log = LoggerFactory.getLogger(PaymentExpiration.class);
  private static final int BATCH = 100;

  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PixSettlement pixSettlement;
  private final BoletoSettlement boletoSettlement;
  private final PaymentEvents events;
  private final PaymentsProperties properties;
  private final UnitOfWork unitOfWork;

  public PaymentExpiration(
      PaymentRepository payments,
      ProviderGateway providers,
      PixSettlement pixSettlement,
      BoletoSettlement boletoSettlement,
      PaymentEvents events,
      PaymentsProperties properties,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.providers = providers;
    this.pixSettlement = pixSettlement;
    this.boletoSettlement = boletoSettlement;
    this.events = events;
    this.properties = properties;
    this.unitOfWork = unitOfWork;
  }

  /**
   * Sweep for PENDING charges past expiry + grace; the per-payment job is the normal path, this is
   * the net.
   */
  public int expireDue(Instant now) {
    List<Payment> due =
        payments.findPendingOlderThan(now.minus(properties.expirationGrace()), BATCH);
    int changed = 0;
    for (Payment payment : due) {
      try {
        if (expireOne(payment.id(), now)) {
          changed++;
        }
      } catch (RuntimeException e) {
        // One bank hiccup must not stop the sweep for every other merchant's charges.
        log.warn("could not expire payment {}", payment.id(), e);
      }
    }
    return changed;
  }

  /**
   * Returns whether the payment changed state. A payment no longer PENDING (or not yet due) is a
   * no-op.
   */
  public boolean expireOne(String paymentId, Instant now) {
    Payment payment = payments.findById(paymentId).orElse(null);
    if (payment == null
        || payment.status() != PaymentStatus.PENDING
        || payment.expiresAt().plus(properties.expirationGrace()).isAfter(now)) {
      return false;
    }
    if (payment.method() == PaymentMethod.BOLECODE) {
      return expireBolecode(
          payment,
          providers.resolveBoleto(payment.merchantId(), payment.environment(), payment.provider()));
    }

    ResolvedProvider<PixMethodProvider> resolved =
        providers.resolvePix(payment.merchantId(), payment.environment(), payment.provider());
    Optional<Charge> atBank =
        providers.call(
            payment.id(),
            "findCharge",
            resolved,
            target -> target.provider().find(target.credentials(), payment.id()));
    if (atBank.isPresent() && atBank.get().status() == ChargeStatus.COMPLETED) {
      if (atBank.get().firstPix().isEmpty()) {
        // Paid, but without the pix[] that says by whom and how much: we cannot complete it, and
        // expiring a charge the bank calls paid would be wrong. Left PENDING for the webhook or the
        // next reconciliation to bring the details.
        log.warn(
            "bank reports payment {} COMPLETED without pix[]; leaving it PENDING", payment.id());
        return false;
      }
      pixSettlement.settle(
          payment.merchantId(),
          payment.id(),
          atBank.get().firstPix().get(),
          EventSource.RECONCILIATION);
      return true;
    }
    if (atBank.isPresent() && atBank.get().status() == ChargeStatus.ACTIVE) {
      try {
        // Best effort: the bank expires the QR on its own clock anyway; removing it just closes
        // the window between our expiry and the bank's.
        providers.run(
            payment.id(),
            "cancelCharge",
            resolved,
            target -> target.provider().cancel(target.credentials(), payment.id()));
      } catch (ProviderException e) {
        if (e.code() != ProviderException.Code.INVALID
            && e.code() != ProviderException.Code.NOT_FOUND) {
          log.info(
              "could not remove expired charge {} at the bank: {}", payment.id(), e.getMessage());
        }
      }
    }
    return markExpired(paymentId);
  }

  /**
   * A Bolecode expires on its payment limit date (spec 2026-09-25 §7, §10): after it the bank
   * refuses both the barcode and the QR, so no baixa is sent — it would only add a call that can
   * fail. The query still runs first: a payment made on the last day is credited on the next
   * business day.
   */
  private boolean expireBolecode(Payment payment, ResolvedProvider<BoletoMethodProvider> resolved) {
    String nn = payment.boleto().nossoNumero();
    Optional<BoletoStatus> atBank =
        providers.call(
            payment.id(),
            "findBoleto",
            resolved,
            target -> target.provider().find(target.credentials(), nn));
    if (atBank.isPresent() && atBank.get().paid()) {
      return boletoSettlement.settleBoleto(
              payment.merchantId(), payment.id(), atBank.get(), EventSource.RECONCILIATION)
          == Settlement.COMPLETED;
    }
    if (atBank.isPresent() && atBank.get().situation() == BoletoSituation.AWAITING_CREDIT) {
      log.info(
          "boleto {} of payment {} awaiting credit at the bank; not expiring yet",
          nn,
          payment.id());
      return false;
    }
    return markExpired(payment.id());
  }

  private boolean markExpired(String paymentId) {
    return Boolean.TRUE.equals(
        unitOfWork.inTransaction(
            () -> {
              Payment loaded = payments.findById(paymentId).orElseThrow();
              if (loaded.status() != PaymentStatus.PENDING) {
                return false; // a webhook or a poll got there while we were asking the bank
              }
              Payment saved =
                  payments.save(loaded, List.of(loaded.markExpired(EventSource.EXPIRATION_JOB)));
              events.emit(saved.merchantId(), "payment.expired", saved);
              return true;
            }));
  }
}
