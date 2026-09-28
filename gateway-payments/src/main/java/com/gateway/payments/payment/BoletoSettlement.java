package com.gateway.payments.payment;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bank's boleto query saying "paid" completing its payment, with the same rules as a Pix: a
 * different amount is a divergence, never a completion, and a Pix channel is recorded as Pix
 * because a Pix settlement is refundable at the bank while a barcode one is not (ruling R3).
 */
public class BoletoSettlement {
  private static final Logger log = LoggerFactory.getLogger(BoletoSettlement.class);

  private final PaymentRepository payments;
  private final Divergences divergences;
  private final PaymentEvents events;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public BoletoSettlement(
      PaymentRepository payments,
      Divergences divergences,
      PaymentEvents events,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.payments = payments;
    this.divergences = divergences;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /**
   * The bank's boleto query says "paid" for {@code paymentId}, from whichever path saw it first
   * (poll, expiration's pre-check, reconciliation, a cancel that lost to the payer). The same rules
   * as {@link #settle} for Pix: PENDING or EXPIRED completes, with the bank's amount and date and
   * {@code paidVia = BOLETO}; a different amount is a divergence, never a completion; a payment
   * already COMPLETED via Pix (or with no paidVia) is DOUBLE_PAYMENT only when the bank names a
   * non-Pix channel, since the Bolecode QR itself settles the boleto — a human decides.
   */
  public Settlement settleBoleto(
      MerchantId merchantId, String paymentId, BoletoStatus status, EventSource by) {
    return unitOfWork.inTransaction(
        () -> {
          Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
          if (found.isEmpty()) {
            return Settlement.UNKNOWN_PAYMENT;
          }
          Payment payment = found.get();
          if (payment.method() != PaymentMethod.BOLECODE || payment.boleto() == null) {
            throw new IllegalStateException(
                "settleBoleto on a " + payment.method() + " payment " + paymentId);
          }
          String nn = payment.boleto().nossoNumero();
          long paidCents = status.paidAmount() == null ? -1 : status.paidAmount().cents();
          if (payment.status() == PaymentStatus.PENDING
              || payment.status() == PaymentStatus.EXPIRED) {
            if (paidCents != payment.amount().cents()) {
              payments.save(
                  payment,
                  List.of(
                      payment
                          .recordIgnored(
                              "boleto "
                                  + nn
                                  + " paid "
                                  + paidCents
                                  + " cents, charge is "
                                  + payment.amount().cents(),
                              by)
                          .orElseThrow()));
              divergences.open(
                  payment,
                  "AMOUNT_MISMATCH",
                  "boleto "
                      + nn
                      + " paid "
                      + paidCents
                      + " cents at the bank, charge is "
                      + payment.amount().cents());
              return Settlement.IGNORED;
            }
            Instant paidAt = status.paidAt() == null ? clock.instant() : status.paidAt();
            // Ruling R3: a Pix channel means the payer used the QR, and a Pix settlement is
            // refundable at
            // the bank while a barcode one is not. Recording BOLETO here would lock the merchant
            // out of a
            // refund they are owed. The query has no endToEndId; BoletoPollingService asks GET /cob
            // for it
            // first, so reaching this with a Pix channel means it stays unknown.
            PaymentEvent completion =
                isPixChannel(status.paidChannel())
                    ? payment.markCompleted(null, status.paidAmount(), paidAt, by)
                    : payment.markCompletedByBoleto(
                        status.paidAmount(), paidAt, status.paidChannel(), by);
            Payment saved = payments.save(payment, List.of(completion));
            events.emit(saved.merchantId(), "payment.completed", saved);
            return Settlement.COMPLETED;
          }
          if (payment.status() == PaymentStatus.COMPLETED) {
            if (payment.boleto().paidVia() == PaidVia.BOLETO) {
              payments.save(
                  payment,
                  List.of(
                      payment
                          .recordIgnored("boleto " + nn + " already settled", by)
                          .orElseThrow()));
              return Settlement.IGNORED;
            }
            // Paying the QR of a Bolecode settles the boleto at the bank too, so "paid" after a Pix
            // completion is normally the same money. Only a channel that is not Pix is a second
            // payment.
            // The channel codes are unconfirmed until the sandbox smoke; a missing one is logged,
            // not flagged.
            String channel = status.paidChannel();
            if (channel == null || channel.isBlank()) {
              log.warn(
                  "boleto {} of payment {} paid at the bank without a payment channel; assumed its own pix",
                  nn,
                  paymentId);
              payments.save(
                  payment,
                  List.of(
                      payment
                          .recordIgnored(
                              "boleto "
                                  + nn
                                  + " paid at the bank, no channel, on a payment completed via PIX",
                              by)
                          .orElseThrow()));
              return Settlement.IGNORED;
            }
            if (isPixChannel(channel)) {
              payments.save(
                  payment,
                  List.of(
                      payment
                          .recordIgnored(
                              "boleto " + nn + " settled by its own pix (" + channel + ")", by)
                          .orElseThrow()));
              return Settlement.IGNORED;
            }
            payments.save(
                payment,
                List.of(
                    payment
                        .recordIgnored(
                            "boleto "
                                + nn
                                + " paid at the bank via "
                                + channel
                                + " on a payment completed via PIX",
                            by)
                        .orElseThrow()));
            divergences.open(
                payment,
                "DOUBLE_PAYMENT",
                "paid via PIX (e2eid "
                    + (payment.pix() == null ? null : payment.pix().endToEndId())
                    + ") and boleto "
                    + nn
                    + " paid "
                    + paidCents
                    + " cents via "
                    + channel);
            return Settlement.IGNORED;
          }
          if (payment.status().terminal()) {
            // Money arrived for a charge the merchant will never hear about again
            // (FAILED/CANCELED).
            payments.save(
                payment,
                List.of(
                    payment
                        .recordIgnored(
                            "boleto " + nn + " paid at the bank while " + payment.status(), by)
                        .orElseThrow()));
            divergences.open(
                payment,
                "BOLETO_PAID",
                "boleto "
                    + nn
                    + " paid "
                    + paidCents
                    + " cents at the bank while "
                    + payment.status());
            return Settlement.IGNORED;
          }
          throw new IllegalStateException(
              "boleto settlement for payment " + paymentId + " still in " + payment.status());
        });
  }

  /**
   * Whether the bank's payment channel is Pix; null or blank is not (the caller decides what an
   * absent channel means).
   */
  public static boolean isPixChannel(String channel) {
    if (channel == null || channel.isBlank()) {
      return false;
    }
    String plain =
        java.text.Normalizer.normalize(channel, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(java.util.Locale.ROOT);
    return plain.contains("pix");
  }
}
