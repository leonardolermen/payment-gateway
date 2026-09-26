package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.boleto.BoletoDates;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.reconciliation.Divergences;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CREATED -> PENDING, with the bank's side of the charge, the jobs it needs and the outbox row —
 * all in one transaction.
 *
 * <p>Idempotent on purpose: a payment no longer CREATED is returned as it is. The stuck-CREATED
 * sweeper and a slow create can race to adopt the same charge, and the loser must not fail on
 * PENDING -> PENDING.
 *
 * <p>Public, not private to a flow: the sweeper ({@code ExpirationService}) and the boleto poll
 * ({@code BoletoPollingService}) adopt too.
 */
public class PendingAdoption {
  private static final Logger log = LoggerFactory.getLogger(PendingAdoption.class);

  private final PaymentRepository payments;
  private final JobRepository jobs;
  private final PaymentEvents events;
  private final Divergences divergences;
  private final PaymentsProperties props;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public PendingAdoption(
      PaymentRepository payments,
      JobRepository jobs,
      PaymentEvents events,
      Divergences divergences,
      PaymentsProperties props,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.payments = payments;
    this.jobs = jobs;
    this.events = events;
    this.divergences = divergences;
    this.props = props;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** With the bank's charge details, plus the expire job and the outbox row. */
  public Payment adoptPix(String paymentId, Charge accepted, int fallbackExpires, EventSource by) {
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          if (payment.status() != PaymentStatus.CREATED) {
            return payment;
          }

          int bankExpiry =
              accepted.expiresInSeconds() > 0 ? accepted.expiresInSeconds() : fallbackExpires;

          // A Pix txid is ours: we PUT /cob/{payment id}. Storing the echoed one broke
          // settleFromWebhook
          // (it looks up by stored txid) against Itau's sandbox mock, whose PUT /cob always answers
          // txid 7978c0c97ea847e78e8849634473c1f1; production echoes ours, so a mismatch is only
          // logged.
          if (accepted.txid() != null && !accepted.txid().equals(payment.id())) {
            log.warn(
                "bank echoed txid {} for payment {}; keeping ours", accepted.txid(), payment.id());
          }

          PaymentEvent event =
              payment.markPending(
                  new PixDetails(payment.id(), accepted.pixCopiaECola(), accepted.location(), null),
                  clock.instant().plusSeconds(bankExpiry),
                  by);
          Payment saved = payments.save(payment, List.of(event));

          enqueueExpiry(saved);
          events.emit(saved.merchantId(), "payment.pending", saved);

          return saved;
        });
  }

  /**
   * With both sides, the expire job (limit date's end of day + grace), the poll job and the outbox
   * row.
   */
  public Payment adoptBolecode(String paymentId, IssuedBoleto issued, EventSource by) {
    return adoptBolecode(paymentId, issued, by, null);
  }

  /**
   * {@code unconfirmedDetail} non-null opens PIX_TXID_UNCONFIRMED in the same transaction, and only
   * if this call made the transition.
   */
  public Payment adoptBolecode(
      String paymentId, IssuedBoleto issued, EventSource by, String unconfirmedDetail) {
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          if (payment.status() != PaymentStatus.CREATED) {
            // The sweeper and a slow create may race to adopt the same boleto; the loser must not
            // fail,
            // and must not flag a txid it did not adopt: the winner's own confirmation is what
            // counts.
            return payment;
          }

          BoletoDetails details =
              payment
                  .boleto()
                  .withIssued(
                      issued.idBoletoIndividual(),
                      issued.linhaDigitavel(),
                      issued.codigoBarras(),
                      issued.paymentLimitDate());
          PixDetails pix = new PixDetails(issued.pixTxid(), issued.pixCopiaECola(), null, null);

          Payment saved =
              payments.save(
                  payment,
                  List.of(
                      payment.markPendingBolecode(
                          pix, details, BoletoDates.endOfDay(details.paymentLimitDate()), by)));

          enqueueExpiry(saved);
          if (!jobs.enqueue(
              Job.pollBoleto(saved.id(), clock.instant().plus(props.boletoPollEvery()), clock))) {
            log.debug("poll job for payment {} was already queued", saved.id());
          }

          events.emit(saved.merchantId(), "payment.pending", saved);

          if (unconfirmedDetail != null) {
            divergences.open(saved, "PIX_TXID_UNCONFIRMED", unconfirmedDetail);
          }

          return saved;
        });
  }

  private void enqueueExpiry(Payment saved) {
    if (!jobs.enqueue(
        Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock))) {
      log.debug("expire job for payment {} was already queued", saved.id());
    }
  }
}
