package com.gateway.payments.payment;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.create.PixPaymentFlow;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A received Pix completing its payment. The bank settles up to the last second, so it wins over
 * our expiry; a Pix of another amount is not "the payment" and opens a divergence instead of a
 * completion; and the webhook is a hint, not the truth: the payment completes only with what the
 * BANK reports.
 */
public class PixSettlement {
  private final PaymentRepository payments;
  private final Divergences divergences;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;

  public PixSettlement(
      PaymentRepository payments,
      Divergences divergences,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.divergences = divergences;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
  }

  /**
   * A Pix the bank says was received for {@code paymentId}, from whichever path saw it first
   * (webhook, expiration's pre-check, reconciliation). PENDING or EXPIRED completes — the bank
   * settles up to the last second, so it wins over our expiry. A payment already terminal records
   * an "ignored" event (a duplicate or late notification is a stored fact, not an error) and emits
   * nothing, so a merchant never gets a second {@code payment.completed}.
   */
  public Settlement settle(
      MerchantId merchantId, String paymentId, ReceivedPix pix, EventSource by) {
    return unitOfWork.inTransaction(
        () -> {
          Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
          if (found.isEmpty()) {
            return Settlement.UNKNOWN_PAYMENT;
          }
          Payment payment = found.get();
          if ((payment.status() == PaymentStatus.PENDING
                  || payment.status() == PaymentStatus.EXPIRED)
              && pix.amount().cents() != payment.amount().cents()) {
            // A charge has a fixed amount; a Pix of another amount is not "the payment". Completing
            // it
            // would tell the merchant to ship an order of 159.90 against 1.00. No transition,
            // nothing to
            // the merchant; a human decides.
            payments.save(
                payment,
                List.of(
                    payment
                        .recordIgnored(
                            "pix "
                                + pix.endToEndId()
                                + " of "
                                + pix.amount().cents()
                                + " cents, charge is "
                                + payment.amount().cents(),
                            by)
                        .orElseThrow()));
            divergences.open(
                payment,
                "AMOUNT_MISMATCH",
                "pix "
                    + pix.endToEndId()
                    + " paid "
                    + pix.amount().cents()
                    + " cents, charge is "
                    + payment.amount().cents());
            return Settlement.IGNORED;
          }
          if (payment.status() == PaymentStatus.PENDING
              || payment.status() == PaymentStatus.EXPIRED) {
            Payment saved =
                payments.save(
                    payment,
                    List.of(
                        payment.markCompleted(pix.endToEndId(), pix.amount(), pix.paidAt(), by)));
            events.emit(saved.merchantId(), "payment.completed", saved);
            return Settlement.COMPLETED;
          }
          if (payment.status().terminal()) {
            String knownE2e = payment.pix() == null ? null : payment.pix().endToEndId();
            boolean sameAmount =
                payment.paidAmount() != null
                    && payment.paidAmount().cents() == pix.amount().cents();
            // A Bolecode the boleto query completed via PIX without an endToEndId (GET /cob
            // unreachable
            // at that moment) has no e2eid to compare: its QR is its only Pix side, so a Pix of the
            // same
            // amount is the one the query already saw, not a second payment.
            boolean completedByQueryViaPix =
                payment.boleto() != null
                    && payment.boleto().paidVia() == PaidVia.PIX
                    && knownE2e == null;
            boolean duplicate =
                payment.status() == PaymentStatus.COMPLETED
                    && sameAmount
                    && (Objects.equals(knownE2e, pix.endToEndId()) || completedByQueryViaPix);
            String what =
                duplicate
                    ? "duplicate e2eid " + pix.endToEndId()
                    : "pix " + pix.endToEndId() + " on a " + payment.status() + " payment";
            payments.save(payment, List.of(payment.recordIgnored(what, by).orElseThrow()));
            if (!duplicate) {
              // Money arrived that the merchant will never hear about (FAILED/CANCELED), or a
              // second,
              // different Pix on a COMPLETED charge. No legal transition moves the payment and
              // nothing
              // goes to the merchant; a human decides (refund the payer, or reopen the order).
              divergences.open(
                  payment,
                  "PIX_RECEIVED",
                  "paid at bank while "
                      + payment.status()
                      + ": e2eid "
                      + pix.endToEndId()
                      + ", "
                      + pix.amount().cents()
                      + " cents");
            }
            return Settlement.IGNORED;
          }
          // CREATED: the bank cannot have been paid for a charge it has not answered yet; failing
          // makes
          // the caller's job retry once the PENDING write lands.
          throw new IllegalStateException(
              "pix for payment " + paymentId + " still in " + payment.status());
        });
  }

  /**
   * A received Pix announced by the bank's webhook. The webhook is a hint, not the truth: anyone
   * who can reach the endpoint with the right token could POST a body saying "paid", and the
   * merchant would ship goods on our {@code payment.completed}. So the bank is asked ({@code GET
   * /cob/{txid}}) and the payment completes only with what the BANK reports: the charge CONCLUIDA
   * with a Pix of the same endToEndId. The amount is then checked by {@link #settle} against the
   * charge's own.
   *
   * <p>Not confirmed: an "ignored" event and an {@code UNCONFIRMED_WEBHOOK} divergence, nothing to
   * the merchant. The bank unreachable: the exception propagates and the inbox job retries.
   */
  public Settlement settleFromWebhook(MerchantId merchantId, String txid, ReceivedPix hinted) {
    // By txid, not by id: a Bolecode's txid is the bank's BL..., and the webhook only knows the
    // txid.
    Optional<Payment> found =
        payments.findByMerchantAndTxid(merchantId, PixPaymentFlow.PROVIDER, txid);
    if (found.isEmpty()) {
      return Settlement.UNKNOWN_PAYMENT;
    }
    Payment payment = found.get();
    if (payment.status() == PaymentStatus.CREATED) {
      throw new IllegalStateException(
          "pix for payment " + payment.id() + " still in " + payment.status());
    }
    ResolvedProvider<PixMethodProvider> resolved =
        providers.resolvePix(merchantId, payment.environment(), payment.provider());
    Optional<Charge> atBank =
        providers.call(
            payment.id(),
            "findCharge",
            resolved,
            target -> target.provider().find(target.credentials(), payment.pix().txid()));
    Optional<ReceivedPix> confirmed =
        atBank
            .filter(
                charge -> charge.status() == ChargeStatus.COMPLETED && charge.received() != null)
            .flatMap(
                charge ->
                    charge.received().stream()
                        .filter(
                            received -> Objects.equals(received.endToEndId(), hinted.endToEndId()))
                        .findFirst());
    if (confirmed.isPresent()) {
      return settle(merchantId, payment.id(), confirmed.get(), EventSource.PROVIDER_WEBHOOK);
    }
    String bankSays = atBank.map(charge -> charge.status().name()).orElse("NOT_FOUND");
    unitOfWork.run(
        () -> {
          Payment loaded = payments.findById(payment.id()).orElseThrow();
          payments.save(
              loaded,
              List.of(
                  loaded
                      .recordIgnored(
                          "unconfirmed webhook: e2eid "
                              + hinted.endToEndId()
                              + ", bank says "
                              + bankSays,
                          EventSource.PROVIDER_WEBHOOK)
                      .orElseThrow()));
          divergences.open(
              loaded,
              "UNCONFIRMED_WEBHOOK",
              "webhook said e2eid "
                  + hinted.endToEndId()
                  + " paid "
                  + hinted.amount().cents()
                  + " cents; bank says "
                  + bankSays);
        });
    return Settlement.IGNORED;
  }
}
