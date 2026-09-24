package com.gateway.payments.service;

import com.gateway.kernel.provider.RefundResult;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.Refund;
import com.gateway.payments.domain.RefundState;
import com.gateway.payments.repository.PaymentRepository;
import com.gateway.payments.repository.RefundRepository;
import java.util.Optional;

/**
 * Closes refunds the webhook did not: refund-status webhooks are opt-in at Itau (NOTES.md), so
 * polling {@code GET /pix/{e2eid}/devolucao/{id}} is the path that always works.
 */
public class RefundPollingService {
  private final RefundRepository refunds;
  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final RefundService refundService;

  public RefundPollingService(RefundRepository refunds, PaymentRepository payments, ProviderGateway providers, RefundService refundService) {
    this.refunds = refunds;
    this.payments = payments;
    this.providers = providers;
    this.refundService = refundService;
  }

  /**
   * Returns {@code true} once the refund is settled either way. {@code false} means "still
   * processing": the {@link JobRunner} polls again in 5 minutes, up to {@code refundPollMaxAttempts}
   * (288, i.e. 24 h); then the job goes DEAD and {@link RefundService#giveUp} marks the refund
   * FAILED and opens a divergence.
   */
  public boolean poll(String refundId) {
    Refund refund = refunds.findById(refundId).orElse(null);
    if (refund == null || terminal(refund)) {
      return true;
    }
    Payment payment = payments.findById(refund.paymentId()).orElseThrow();
    ProviderGateway.Resolved r = providers.resolve(payment.merchantId(), payment.environment(), payment.provider());
    Optional<RefundResult> result =
        providers.call(payment.id(), "findRefund", r, x -> x.provider().findRefund(x.credentials(), payment.pix().endToEndId(), refundId));
    if (result.isEmpty()) {
      return false;
    }
    refundService.applyProviderUpdate(result.get());
    return refunds.findById(refundId).map(RefundPollingService::terminal).orElse(true);
  }

  private static boolean terminal(Refund r) {
    return r.state() == RefundState.COMPLETED || r.state() == RefundState.FAILED;
  }
}
