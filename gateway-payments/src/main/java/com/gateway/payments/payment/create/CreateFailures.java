package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failing a create: CREATED -> FAILED with its event and outbox row, and the exception to throw
 * back.
 */
public class CreateFailures {
  private static final Logger log = LoggerFactory.getLogger(CreateFailures.class);

  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;

  public CreateFailures(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
  }

  /**
   * With {@code resolved} set (the charge's fate is unknown), also asks the bank to remove the
   * charge, best effort: if the PUT did land after all, a QR we reported as failed must not stay
   * payable.
   */
  public DomainException fail(
      String paymentId,
      String code,
      ProviderException cause,
      ResolvedProvider<PixMethodProvider> resolved) {
    if (resolved != null) {
      try {
        providers.run(
            paymentId,
            "cancelCharge",
            resolved,
            target -> target.provider().cancel(target.credentials(), paymentId));
      } catch (RuntimeException ignored) {
        // NOT_FOUND is the expected answer; anything else is left to reconciliation.
      }
    }

    markFailed(paymentId, code, EventSource.API);

    return ProviderErrors.toDomain(code, cause, log, "createCharge", paymentId);
  }

  /** CREATED -> FAILED with event and outbox row; a payment no longer CREATED is left alone. */
  public void markFailed(String paymentId, String code, EventSource by) {
    unitOfWork.run(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          if (payment.status() != PaymentStatus.CREATED) {
            return;
          }

          Payment saved = payments.save(payment, List.of(payment.markFailed(code, by)));
          events.emit(saved.merchantId(), "payment.failed", saved);
        });
  }
}
