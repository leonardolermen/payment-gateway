package com.gateway.payments.refund;

import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.RefundResult;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.refund.persistence.RefundRepository;
import java.time.Clock;
import java.time.Instant;
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
  private final PaymentsProperties props;
  private final Clock clock;

  public RefundPollingService(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      RefundService refundService,
      PaymentsProperties props,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.providers = providers;
    this.refundService = refundService;
    this.props = props;
    this.clock = clock;
  }

  /**
   * Returns {@code true} once the refund is settled either way. {@code false} means "still
   * processing": the {@link JobRunner} polls again in 5 minutes, up to {@code
   * refundPollMaxAttempts} (288, i.e. 24 h); then the job goes DEAD and {@link
   * RefundService#giveUp} marks the refund UNKNOWN (amount still reserved) and opens a divergence.
   *
   * <p>A refund the bank does not know ({@code findRefund} empty) is one whose PUT timed out or got
   * a 503 and never landed: after {@code refundNotFoundGrace} it is FAILED, which frees the amount.
   * Before the grace it may just not be visible yet.
   */
  public boolean poll(String refundId) {
    Refund refund = refunds.findById(refundId).orElse(null);
    if (refund == null || terminal(refund)) {
      return true;
    }
    Payment payment = payments.findById(refund.paymentId()).orElseThrow();
    ResolvedProvider<PixMethodProvider> resolved =
        providers.resolvePix(payment.merchantId(), payment.environment(), payment.provider());
    Optional<RefundResult> result =
        providers.call(
            payment.id(),
            "findRefund",
            resolved,
            target ->
                target
                    .provider()
                    .findRefund(target.credentials(), payment.pix().endToEndId(), refundId));
    if (result.isEmpty()) {
      Instant cutoff = refund.createdAt().plus(props.refundNotFoundGrace());
      if (clock.instant().isAfter(cutoff)) {
        refundService.applyProviderUpdate(
            new RefundResult(
                refundId,
                com.gateway.kernel.provider.pix.RefundStatus.FAILED,
                refund.amount(),
                "refund not found at the bank",
                refund.createdAt(),
                null));
        return true;
      }
      return false;
    }
    refundService.applyProviderUpdate(result.get());
    return refunds.findById(refundId).map(RefundPollingService::terminal).orElse(true);
  }

  private static boolean terminal(Refund r) {
    return r.state() == RefundState.COMPLETED
        || r.state() == RefundState.FAILED
        || r.state() == RefundState.UNKNOWN;
  }
}
