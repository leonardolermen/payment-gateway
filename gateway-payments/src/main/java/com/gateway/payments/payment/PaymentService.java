package com.gateway.payments.payment;

import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.payments.payment.create.BolecodeFromQuery;
import com.gateway.payments.payment.create.CreateFailures;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.gateway.payments.payment.create.PaymentFlows;
import com.gateway.payments.payment.create.PendingAdoption;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;

/**
 * Creating a payment and the create's own loose ends: the adoptions the sweeper and the boleto poll
 * reach, a create that failed, and a divergence opened once. Reading, cancelling and settling live
 * in {@link PaymentQueries}, {@link PaymentCancellation}, {@link PixSettlement} and {@link
 * BoletoSettlement}.
 */
public class PaymentService {
  private final Divergences divergences;
  private final PaymentFlows flows;
  private final PendingAdoption adoption;
  private final BolecodeFromQuery bolecodeFromQuery;
  private final CreateFailures failures;

  public PaymentService(
      Divergences divergences,
      PaymentFlows flows,
      PendingAdoption adoption,
      BolecodeFromQuery bolecodeFromQuery,
      CreateFailures failures) {
    this.divergences = divergences;
    this.flows = flows;
    this.adoption = adoption;
    this.bolecodeFromQuery = bolecodeFromQuery;
    this.failures = failures;
  }

  /**
   * Creating a payment is the method's business: {@link PaymentFlows} holds one flow per method and
   * each owns its create end to end. This service keeps the rest of the lifecycle.
   */
  public Payment create(CreatePaymentCommand command) {
    return flows.forMethod(command.method()).create(command);
  }

  /**
   * CREATED -> PENDING with the bank's charge details. Reached from here by the stuck-CREATED
   * sweeper; the flow calls {@link PendingAdoption} directly.
   */
  public Payment adoptPending(
      String paymentId, Charge accepted, int fallbackExpires, EventSource by) {
    return adoption.adoptPix(paymentId, accepted, fallbackExpires, by);
  }

  /** CREATED -> PENDING with both sides and both jobs. Reached from here by the boleto poll. */
  public Payment adoptPendingBolecode(String paymentId, IssuedBoleto issued, EventSource by) {
    return adoption.adoptBolecode(paymentId, issued, by);
  }

  /**
   * The issue's answer was lost; the query has the boleto's identity but not the Pix side. See
   * {@link BolecodeFromQuery}, which the sweeper reaches through here.
   */
  public Payment adoptBolecodeFromStatus(
      String paymentId,
      ResolvedProvider<BoletoMethodProvider> resolved,
      BoletoStatus status,
      EventSource by) {
    return bolecodeFromQuery.adopt(paymentId, resolved, status, by);
  }

  /** CREATED -> FAILED with event and outbox row; a payment no longer CREATED is left alone. */
  public void markFailed(String paymentId, String code, EventSource by) {
    failures.markFailed(paymentId, code, by);
  }

  /**
   * Opens a divergence unless the same (payment, provider status) one is already OPEN.
   * Reconciliation runs every 15 minutes over a 48 h window: without the check one mismatch would
   * open ~190 identical divergences before anyone looked at the first. The check is the database's
   * (partial unique index, V201), not a scan of every OPEN row.
   */
  public boolean openDivergence(Payment payment, String providerStatus, String detail) {
    return divergences.open(payment, providerStatus, detail);
  }
}
