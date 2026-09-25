package com.gateway.payments.reconciliation;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.provider.ProviderGateway;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.boleto.BoletoPollingService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares our view with the bank's {@code GET /cob?inicio&fim}. The bank is the source of truth
 * for money: a charge it says was paid and we have as PENDING/EXPIRED is completed here (the
 * webhook was lost). Anything else that disagrees — a COMPLETED payment the bank shows as removed
 * or still active, paid by another endToEndId, or with a different amount — is NOT fixed
 * automatically: it opens a divergence for a human, because the state machine has no legal
 * transition out of COMPLETED and guessing would be worse.
 */
public class ReconciliationService {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final int CANDIDATES = 1000;

  private record Scope(MerchantId merchantId, ProviderEnvironment env) {}

  private final PaymentRepository payments;
  private final ReconciliationDivergenceRepository divergences;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final BoletoPollingService boletoPolling;
  private final PaymentsProperties props;
  private final Clock clock;

  public ReconciliationService(
      PaymentRepository payments,
      ReconciliationDivergenceRepository divergences,
      ProviderGateway providers,
      PaymentService paymentService,
      BoletoPollingService boletoPolling,
      PaymentsProperties props,
      Clock clock) {
    this.payments = payments;
    this.divergences = divergences;
    this.providers = providers;
    this.paymentService = paymentService;
    this.boletoPolling = boletoPolling;
    this.props = props;
    this.clock = clock;
  }

  /**
   * Scopes are the (merchant, environment) pairs that have something worth checking in the last
   * {@code reconciliationLookback}: PENDING older than {@code reconciliationMinAge} (younger ones
   * are still waiting for their webhook, normally), EXPIRED, COMPLETED (to catch removals), and
   * FAILED/CANCELED (a charge we gave up on that the payer paid anyway). Oldest first
   * ({@code findByStatusIn} orders by {@code created_at} ascending), so the cap drains a backlog
   * run after run instead of re-reading the newest rows forever.
   */
  public int reconcileAll(Instant now) {
    Instant from = now.minus(props.reconciliationLookback());
    Instant youngCutoff = now.minus(props.reconciliationMinAge());
    int changed = 0;
    // Bolecode, barcode side: there is no listing API for boletos, so each one is checked one by one
    // with the same decision table as the poll (BoletoPollingService). PENDING older than minAge, and
    // CANCELED/FAILED too (ruling R2): a printed barcode can still be paid after a baixa or a failed
    // issue, and a FAILED Bolecode never had a poll job, so this pass is the only one that sees it.
    for (Payment p : payments.findByMethodAndStatusIn(PaymentMethod.BOLECODE, EnumSet.of(PaymentStatus.PENDING, PaymentStatus.CANCELED, PaymentStatus.FAILED), from, CANDIDATES)) {
      if (p.status() == PaymentStatus.PENDING && p.createdAt().isAfter(youngCutoff)) {
        continue;
      }
      try {
        PaymentStatus before = p.status();
        boletoPolling.check(p.id(), EventSource.RECONCILIATION);
        if (payments.findById(p.id()).map(x -> x.status() != before).orElse(false)) {
          changed++;
        }
      } catch (RuntimeException e) {
        log.warn("boleto reconciliation failed for payment {}", p.id(), e);
      }
    }
    Map<Scope, Instant> scopes = new LinkedHashMap<>();
    for (Payment p : payments.findByStatusIn(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, PaymentStatus.FAILED, PaymentStatus.CANCELED), from, CANDIDATES)) {
      if (p.status() == PaymentStatus.PENDING && p.createdAt().isAfter(youngCutoff)) {
        continue;
      }
      scopes.merge(new Scope(p.merchantId(), p.environment()), p.createdAt(), (a, b) -> a.isBefore(b) ? a : b);
    }
    for (Map.Entry<Scope, Instant> s : scopes.entrySet()) {
      try {
        changed += reconcile(s.getKey().merchantId(), s.getKey().env(), s.getValue(), now);
      } catch (RuntimeException e) {
        // A merchant with a revoked credential must not stop reconciliation for everyone else.
        log.warn("reconciliation failed for merchant {} {}", s.getKey().merchantId().value(), s.getKey().env(), e);
      }
    }
    return changed;
  }

  /** Returns how many payments were completed or got a new divergence. */
  public int reconcile(MerchantId merchantId, ProviderEnvironment env, Instant from, Instant to) {
    ProviderGateway.Resolved r = providers.resolve(merchantId, env, PaymentService.PROVIDER);
    List<Charge> charges = providers.call(null, "listCharges", r, x -> x.provider().listCharges(x.credentials(), from, to));
    int changed = 0;
    for (Charge charge : charges) {
      Optional<Payment> found = payments.findByMerchantAndTxid(merchantId, PaymentService.PROVIDER, charge.txid());
      if (found.isEmpty()) {
        continue; // not ours, or another merchant's with the same bank account
      }
      Payment p = found.get();
      Optional<ReceivedPix> pix = charge.firstPix();
      boolean bankPaid = charge.status() == ChargeStatus.COMPLETED && pix.isPresent();
      if (bankPaid && (p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED)) {
        paymentService.settle(merchantId, p.id(), pix.get(), EventSource.RECONCILIATION);
        changed++;
      } else if (bankPaid && (p.status() == PaymentStatus.FAILED || p.status() == PaymentStatus.CANCELED)) {
        // Same rule as a late webhook (PaymentService.settle), minus the "ignored" event: this runs
        // every 15 minutes and must not grow the payment's log each time it looks.
        changed += open(p, "PIX_RECEIVED", "paid at bank while " + p.status() + ": e2eid " + pix.get().endToEndId() + ", " + pix.get().amount().cents() + " cents");
      } else if (p.status() == PaymentStatus.COMPLETED && charge.status() != ChargeStatus.COMPLETED) {
        // Removed, and equally still ACTIVE: we told the merchant "paid" for a charge the bank never
        // concluded. Since the webhook is confirmed with the bank this should not happen; if it does,
        // a human must see it before the goods go out.
        changed += open(p, charge.status().name(), "gateway has COMPLETED, bank shows the charge as " + charge.status());
      } else if (p.status() == PaymentStatus.COMPLETED
          && bankPaid
          && p.pix() != null
          && charge.received().stream().noneMatch(x -> java.util.Objects.equals(x.endToEndId(), p.pix().endToEndId()))) {
        changed += open(p, charge.status().name(), "e2eid differs: gateway " + p.pix().endToEndId() + ", bank " + pix.get().endToEndId());
      } else if (p.status() == PaymentStatus.COMPLETED && bankPaid && p.paidAmount() != null && pix.get().amount().cents() != p.paidAmount().cents()) {
        changed += open(p, charge.status().name(), "paid amount differs: gateway " + p.paidAmount().cents() + ", bank " + pix.get().amount().cents());
      }
    }
    return changed;
  }

  private int open(Payment p, String providerStatus, String detail) {
    return paymentService.openDivergence(p, providerStatus, detail) ? 1 : 0;
  }
}
