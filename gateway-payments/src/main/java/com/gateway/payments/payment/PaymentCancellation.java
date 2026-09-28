package com.gateway.payments.payment;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancelling a pending charge, the bank first. Deliberately NOT {@code @Transactional}: the bank
 * call runs outside any transaction (a 30 s bank timeout must not hold a pooled connection and row
 * locks for 30 s), and {@link UnitOfWork} wraps only the write after it.
 */
public class PaymentCancellation {
  private static final Logger log = LoggerFactory.getLogger(PaymentCancellation.class);

  private final PaymentQueries queries;
  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;
  private final BoletoSettlement boletoSettlement;

  public PaymentCancellation(
      PaymentQueries queries,
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork,
      BoletoSettlement boletoSettlement) {
    this.queries = queries;
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
    this.boletoSettlement = boletoSettlement;
  }

  public Payment cancel(MerchantId merchantId, String id) {
    Payment current = queries.get(merchantId, id);
    if (current.status() != PaymentStatus.PENDING) {
      throw new DomainException(
          "INVALID_STATE",
          "only a pending payment can be canceled, this one is " + current.status());
    }
    if (current.method() == PaymentMethod.BOLECODE) {
      cancelBoletoAtBank(
          current, providers.resolveBoleto(merchantId, current.environment(), current.provider()));
    } else {
      ResolvedProvider<PixMethodProvider> resolved =
          providers.resolvePix(merchantId, current.environment(), current.provider());
      try {
        providers.run(
            id,
            "cancelCharge",
            resolved,
            target -> target.provider().cancel(target.credentials(), id));
      } catch (ProviderException e) {
        // The bank refuses to remove a charge that is no longer ATIVA — most likely it was just
        // paid
        // and the webhook is on its way. Cancelling here would contradict the bank.
        if (e.code() == ProviderException.Code.INVALID) {
          throw new DomainException(
              "INVALID_STATE", "the bank no longer accepts cancelling this charge");
        }
        throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "cancelCharge", id);
      }
    }
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findByMerchantAndId(merchantId, id).orElseThrow();
          if (payment.status() != PaymentStatus.PENDING) {
            throw new DomainException(
                "INVALID_STATE", "payment changed to " + payment.status() + " while cancelling");
          }
          Payment saved = payments.save(payment, List.of(payment.markCanceled(EventSource.API)));
          events.emit(saved.merchantId(), "payment.canceled", saved);
          return saved;
        });
  }

  /**
   * The bank first (spec §7): a barcode payment is only visible through the query, and a baixa on a
   * paid boleto would contradict money that already arrived. Paid → the payment completes here and
   * the caller gets ALREADY_PAID (a 409 at the edge). Open → baixa; the bank's CONFLICT means it
   * was paid between the two calls, so the query is asked once more and decides. The QR dies with
   * the boleto (product docs); if it does not, expiration covers it.
   */
  private void cancelBoletoAtBank(
      Payment payment, ResolvedProvider<BoletoMethodProvider> resolved) {
    String nossoNumero = payment.boleto().nossoNumero();
    Optional<BoletoStatus> before = findBoletoForCancel(payment, resolved, nossoNumero);
    if (before.isPresent() && before.get().paid()) {
      throw alreadyPaid(payment, before.get());
    }
    try {
      providers.run(
          payment.id(),
          "cancelBoleto",
          resolved,
          target -> target.provider().cancel(target.credentials(), nossoNumero));
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CONFLICT) {
        Optional<BoletoStatus> after = findBoletoForCancel(payment, resolved, nossoNumero);
        if (after.isPresent() && after.get().paid()) {
          throw alreadyPaid(payment, after.get());
        }
        throw new DomainException(
            "INVALID_STATE", "the bank no longer accepts cancelling this boleto");
      }
      if (e.code() == ProviderException.Code.NOT_FOUND) {
        return; // nothing to invalidate at the bank; the gateway side is still canceled below
      }
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "cancelBoleto", payment.id());
    }
  }

  /**
   * A request caller: a timeout or a 503 on the query must reach the merchant as PROVIDER_*, not as
   * a raw ProviderException (a 500), and the payment stays PENDING because nothing was decided.
   */
  private Optional<BoletoStatus> findBoletoForCancel(
      Payment payment, ResolvedProvider<BoletoMethodProvider> resolved, String nossoNumero) {
    try {
      return providers.call(
          payment.id(),
          "findBoleto",
          resolved,
          target -> target.provider().find(target.credentials(), nossoNumero));
    } catch (ProviderException e) {
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "findBoleto", payment.id());
    }
  }

  private DomainException alreadyPaid(Payment payment, BoletoStatus status) {
    if (boletoSettlement.settleBoleto(
            payment.merchantId(), payment.id(), status, EventSource.RECONCILIATION)
        == Settlement.COMPLETED) {
      return new DomainException(
          "ALREADY_PAID", "the bank shows this boleto paid; the payment is now COMPLETED");
    }
    // settleBoleto refused to complete (an amount mismatch, say) and opened a divergence. The
    // cancel
    // is still refused because the bank holds money for it, but claiming COMPLETED would be false.
    return new DomainException(
        "ALREADY_PAID",
        "the bank reports a payment for this boleto that is under review (divergence opened); the payment status is unchanged");
  }
}
