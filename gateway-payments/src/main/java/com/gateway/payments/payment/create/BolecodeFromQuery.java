package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.Optional;

/**
 * Adopting a boleto the bank has but we never got the answer for: the issue's response was lost, so the
 * query carries the boleto's identity while the Pix side is still unknown.
 *
 * <p>The txid follows the bank's documented formula ({@code BoletoMethodProvider.pixTxidFor}) and is
 * confirmed with GET /cob/{txid}, which also yields the EMV. If the bank does not know that txid, the
 * query's own {@code qrcode_pix.emv} is used and a divergence records that the formula did not match —
 * the payment is still ADOPTED (ruling R1). Refusing it would leave a boleto the bank issued sitting in
 * CREATED, and the sweeper would later fail it while it is still payable.
 */
public class BolecodeFromQuery {
  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PendingAdoption adoption;

  public BolecodeFromQuery(PaymentRepository payments, ProviderGateway providers, PendingAdoption adoption) {
    this.payments = payments;
    this.providers = providers;
    this.adoption = adoption;
  }

  public Payment adopt(
      String paymentId, ResolvedProvider<BoletoMethodProvider> resolved, BoletoStatus status, EventSource by) {
    Payment payment = payments.findById(paymentId).orElseThrow();
    String nossoNumero = payment.boleto().nossoNumero();

    String txid = resolved.provider().pixTxidFor(resolved.credentials(), nossoNumero);

    // The Pix side of the same charge, through the Pix door: the boleto product does not read /cob.
    ResolvedProvider<PixMethodProvider> pixSide =
        providers.resolvePix(payment.merchantId(), payment.environment(), payment.provider());
    Optional<Charge> charge =
        providers.call(paymentId, "findCharge", pixSide, target -> target.provider().find(target.credentials(), txid));

    String emv = charge.map(Charge::pixCopiaECola).orElse(status.pixCopiaECola());
    String unconfirmed =
        charge.isEmpty() ? "GET /cob/" + txid + " empty while adopting boleto " + nossoNumero + " from the query" : null;

    IssuedBoleto issued =
        new IssuedBoleto(
            status.idBoletoIndividual(),
            status.linhaDigitavel(),
            status.codigoBarras(),
            status.paymentLimitDate(),
            txid,
            emv,
            null);

    return adoption.adoptBolecode(paymentId, issued, by, unconfirmed);
  }
}
