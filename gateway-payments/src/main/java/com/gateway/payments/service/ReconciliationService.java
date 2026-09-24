package com.gateway.payments.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.Charge;
import com.gateway.kernel.provider.ChargeStatus;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ReceivedPix;
import com.gateway.payments.domain.EventSource;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.domain.ReconciliationDivergence;
import com.gateway.payments.repository.PaymentRepository;
import com.gateway.payments.repository.ReconciliationDivergenceRepository;
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
 * webhook was lost). Anything else that disagrees — a COMPLETED payment the bank shows as removed,
 * or paid with a different amount — is NOT fixed automatically: it opens a divergence for a human,
 * because the state machine has no legal transition out of COMPLETED and guessing would be worse.
 */
public class ReconciliationService {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final int CANDIDATES = 1000;

  private record Scope(MerchantId merchantId, ProviderEnvironment env) {}

  private final PaymentRepository payments;
  private final ReconciliationDivergenceRepository divergences;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final PaymentsProperties props;
  private final Clock clock;

  public ReconciliationService(
      PaymentRepository payments,
      ReconciliationDivergenceRepository divergences,
      ProviderGateway providers,
      PaymentService paymentService,
      PaymentsProperties props,
      Clock clock) {
    this.payments = payments;
    this.divergences = divergences;
    this.providers = providers;
    this.paymentService = paymentService;
    this.props = props;
    this.clock = clock;
  }

  /**
   * Scopes are the (merchant, environment) pairs that have something worth checking in the last
   * {@code reconciliationLookback}: PENDING older than {@code reconciliationMinAge} (younger ones
   * are still waiting for their webhook, normally), EXPIRED, and COMPLETED (to catch removals).
   */
  public int reconcileAll(Instant now) {
    Instant from = now.minus(props.reconciliationLookback());
    Instant youngCutoff = now.minus(props.reconciliationMinAge());
    Map<Scope, Instant> scopes = new LinkedHashMap<>();
    for (Payment p : payments.findByStatusIn(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.EXPIRED, PaymentStatus.COMPLETED), from, CANDIDATES)) {
      if (p.status() == PaymentStatus.PENDING && p.createdAt().isAfter(youngCutoff)) {
        continue;
      }
      scopes.merge(new Scope(p.merchantId(), p.environment()), p.createdAt(), (a, b) -> a.isBefore(b) ? a : b);
    }
    int changed = 0;
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
      Optional<Payment> found = payments.findByMerchantAndId(merchantId, charge.txid());
      if (found.isEmpty()) {
        continue; // not ours, or another merchant's with the same bank account
      }
      Payment p = found.get();
      Optional<ReceivedPix> pix = charge.firstPix();
      boolean bankPaid = charge.status() == ChargeStatus.COMPLETED && pix.isPresent();
      if (bankPaid && (p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED)) {
        paymentService.settle(merchantId, p.id(), pix.get(), EventSource.RECONCILIATION);
        changed++;
      } else if (p.status() == PaymentStatus.COMPLETED && isRemoved(charge.status())) {
        changed += open(p, charge.status().name(), "bank shows the charge as " + charge.status());
      } else if (p.status() == PaymentStatus.COMPLETED && bankPaid && p.paidAmount() != null && pix.get().amount().cents() != p.paidAmount().cents()) {
        changed += open(p, charge.status().name(), "paid amount differs: gateway " + p.paidAmount().cents() + ", bank " + pix.get().amount().cents());
      }
    }
    return changed;
  }

  private static boolean isRemoved(ChargeStatus s) {
    return s == ChargeStatus.REMOVED_BY_MERCHANT || s == ChargeStatus.REMOVED_BY_PSP;
  }

  // Runs every 15 minutes over a 48 h window: without this check one mismatch would open ~190
  // identical divergences before anyone looked at the first.
  private int open(Payment p, String providerStatus, String detail) {
    boolean alreadyOpen = divergences.open().stream().anyMatch(d -> d.paymentId().equals(p.id()) && d.providerStatus().equals(providerStatus));
    if (alreadyOpen) {
      return 0;
    }
    divergences.save(new ReconciliationDivergence(Ulid.next(), p.id(), p.status().name(), providerStatus, detail, "OPEN", clock.instant()));
    return 1;
  }
}
