package com.gateway.payments.payment;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.create.CardAuthorizationRecovery;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adopting or failing CREATED payments the bank may have accepted. Without this, a charge the bank
 * accepted stays CREATED forever and its payment would never reach the merchant.
 */
public class StuckCreatedSweep {
  private static final Logger log = LoggerFactory.getLogger(StuckCreatedSweep.class);
  private static final int BATCH = 100;

  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final PixSettlement pixSettlement;
  private final BoletoSettlement boletoSettlement;
  private final PaymentsProperties properties;
  private final CardAuthorizationRecovery cardRecovery;

  public StuckCreatedSweep(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PixSettlement pixSettlement,
      BoletoSettlement boletoSettlement,
      PaymentsProperties properties,
      CardAuthorizationRecovery cardRecovery) {
    this.payments = payments;
    this.providers = providers;
    this.paymentService = paymentService;
    this.pixSettlement = pixSettlement;
    this.boletoSettlement = boletoSettlement;
    this.properties = properties;
    this.cardRecovery = cardRecovery;
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
    for (Payment payment :
        payments.findByStatusCreatedBefore(
            PaymentStatus.CREATED, now.minus(properties.stuckCreatedAfter()), BATCH)) {
      try {
        if (payment.method() == PaymentMethod.CARD) {
          // The MerchantOrderId is ours, so the Cielo can say what became of the sale; empty or
          // still in doubt after stuckCreatedAfter is FAILED (spec §6.4). A failed query throws and
          // is logged below: the next run asks again.
          cardRecovery.sweep(payment);
          if (payments
              .findById(payment.id())
              .map(reloaded -> reloaded.status() != PaymentStatus.CREATED)
              .orElse(false)) {
            changed++;
          }
          continue;
        }
        if (payment.method() == PaymentMethod.BOLECODE) {
          // Same idea as Pix, with the query: the number is ours, so the bank can say whether the
          // issue landed. Empty after stuckCreatedAfter (the bank's 202 long past) is FAILED.
          ResolvedProvider<BoletoMethodProvider> resolved =
              providers.resolveBoleto(
                  payment.merchantId(), payment.environment(), payment.provider());
          String nossoNumero = payment.boleto().nossoNumero();
          Optional<BoletoStatus> atBank =
              providers.call(
                  payment.id(),
                  "findBoleto",
                  resolved,
                  target -> target.provider().find(target.credentials(), nossoNumero));
          if (atBank.isEmpty()) {
            paymentService.markFailed(payment.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
          } else {
            paymentService.adoptBolecodeFromStatus(
                payment.id(), resolved, atBank.get(), EventSource.SYSTEM);
            if (atBank.get().paid()) {
              boletoSettlement.settleBoleto(
                  payment.merchantId(), payment.id(), atBank.get(), EventSource.RECONCILIATION);
            }
          }
          // Counted from the row, not assumed: every branch above may be a no-op (markFailed and
          // the
          // adoption both leave a payment that is no longer CREATED alone), and counting those made
          // the sweep report work it did not do.
          if (payments
              .findById(payment.id())
              .map(reloaded -> reloaded.status() != PaymentStatus.CREATED)
              .orElse(false)) {
            changed++;
          }
          continue;
        }
        ResolvedProvider<PixMethodProvider> resolved =
            providers.resolvePix(payment.merchantId(), payment.environment(), payment.provider());
        Optional<Charge> atBank =
            providers.call(
                payment.id(),
                "findCharge",
                resolved,
                target -> target.provider().find(target.credentials(), payment.id()));

        if (atBank.isEmpty()) {
          paymentService.markFailed(payment.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
        } else {
          int fallback =
              (int)
                  java.time.Duration.between(payment.createdAt(), payment.expiresAt()).toSeconds();
          paymentService.adoptPending(payment.id(), atBank.get(), fallback, EventSource.SYSTEM);
          if (atBank.get().status() == ChargeStatus.COMPLETED
              && atBank.get().firstPix().isPresent()) {
            pixSettlement.settle(
                payment.merchantId(),
                payment.id(),
                atBank.get().firstPix().get(),
                EventSource.RECONCILIATION);
          }
        }
        changed++;
      } catch (RuntimeException e) {
        log.warn("could not resolve stuck CREATED payment {}", payment.id(), e);
      }
    }
    return changed;
  }
}
