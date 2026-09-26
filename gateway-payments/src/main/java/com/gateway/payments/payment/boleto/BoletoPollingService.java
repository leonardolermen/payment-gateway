package com.gateway.payments.payment.boleto;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The barcode side of a Bolecode has no webhook (spec 2026-09-25 §8): this is how the gateway
 * learns that a boleto was paid. One decision table (spec §7) shared by the POLL_BOLETO job
 * (PROVIDER_POLL) and reconciliation (RECONCILIATION); the bank call runs outside any transaction.
 * {@link #check} answers "is this job finished?", not "did anything change": the runner reschedules
 * a {@code false} every {@code boletoPollEvery} until the limit date plus a grace.
 */
public class BoletoPollingService {
  private static final Logger log = LoggerFactory.getLogger(BoletoPollingService.class);
  static final String NOT_FOUND_MARK = "boleto not found at the bank";

  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final PaymentsProperties props;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public BoletoPollingService(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PaymentsProperties props,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.payments = payments;
    this.providers = providers;
    this.paymentService = paymentService;
    this.props = props;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public boolean check(String paymentId, EventSource by) {
    Payment payment = payments.findById(paymentId).orElse(null);
    if (payment == null || payment.method() != PaymentMethod.BOLECODE || payment.boleto() == null) {
      return true;
    }
    if (payment.status() == PaymentStatus.CREATED) {
      return true; // CREATED is the stuck-CREATED sweeper's business, not the poll's
    }
    ResolvedProvider<BoletoMethodProvider> resolved =
        providers.resolveBoleto(payment.merchantId(), payment.environment(), payment.provider());
    String nn = payment.boleto().nossoNumero();
    Optional<BoletoStatus> atBank =
        providers.call(
            payment.id(),
            "findBoleto",
            resolved,
            target -> target.provider().find(target.credentials(), nn));
    if (payment.status() == PaymentStatus.CANCELED || payment.status() == PaymentStatus.FAILED) {
      return gaveUp(payment, nn, atBank, by);
    }
    if (atBank.isEmpty()) {
      return notFound(payment, nn, by);
    }
    BoletoStatus status = atBank.get();
    if (payment.status() == PaymentStatus.COMPLETED) {
      if (status.paid()) {
        paymentService.settleBoleto(
            payment.merchantId(),
            payment.id(),
            status,
            by); // duplicate or DOUBLE_PAYMENT, decided there
      } else {
        record(payment.id(), "poll after completion: bank says " + status.situation(), by);
      }
      return true;
    }
    return switch (status.situation()) {
      case OPEN, AWAITING_CREDIT -> pastWindow(payment);
      case PAID, SETTLED, CREDITED -> {
        settlePaid(payment, status, by);
        yield true;
      }
      case PAYMENT_REJECTED -> {
        // The bank refused a payment attempt (wrong amount, closed account, ...); the boleto is
        // still open for the payer.
        paymentService.openDivergence(
            payment,
            "BOLETO_REJECTED",
            "bank rejected a payment of boleto " + nn + "; still open for the payer");
        yield pastWindow(payment);
      }
      case CANCELED -> {
        // Someone did a baixa outside the gateway (bankline, another system). The state is not
        // moved: the merchant did not ask for it.
        paymentService.openDivergence(
            payment,
            "CANCELED_AT_BANK",
            "boleto " + nn + " baixado at the bank while the gateway has " + payment.status());
        yield true;
      }
    };
  }

  /**
   * CANCELED and FAILED are watched until the limit date plus the grace (controller ruling R2): a
   * baixa does not reach a payer holding a printed barcode whose bank has not synced yet, and a
   * FAILED issue may have landed after all. A paid barcode there is money the merchant will never
   * hear about, so it must land as a BOLETO_PAID divergence. Anything else (OPEN while the baixa
   * propagates, CANCELED, absent for an issue the bank refused) is the expected picture and is left
   * silent: a NOT_FOUND_AT_BANK on every declined Bolecode would bury the real ones.
   */
  private boolean gaveUp(
      Payment payment, String nn, Optional<BoletoStatus> atBank, EventSource by) {
    if (atBank.isEmpty() || !atBank.get().paid()) {
      return pastWindow(payment);
    }
    if (by == EventSource.RECONCILIATION) {
      // Reconciliation runs every 15 minutes over the lookback: going through settleBoleto would
      // add
      // an "ignored" event per run to the payment's log. The divergence alone is deduplicated.
      long cents = atBank.get().paidAmount() == null ? -1 : atBank.get().paidAmount().cents();
      paymentService.openDivergence(
          payment,
          "BOLETO_PAID",
          "boleto " + nn + " paid " + cents + " cents at the bank while " + payment.status());
    } else {
      paymentService.settleBoleto(payment.merchantId(), payment.id(), atBank.get(), by);
    }
    return true;
  }

  /**
   * A barcode payment settles as BOLETO. A Pix channel on a still-open Bolecode means the payer
   * used the QR and the poll saw it before the webhook (controller ruling R3): the payment must
   * complete with paidVia PIX, or the merchant could never refund it. The boleto query carries no
   * endToEndId, so the charge is asked for it first, best effort: with it, this is the ordinary Pix
   * completion (refunds and the later webhook's duplicate check work as usual); without it,
   * settleBoleto still completes via PIX with the endToEndId unknown.
   */
  private void settlePaid(Payment payment, BoletoStatus status, EventSource by) {
    if (PaymentService.isPixChannel(status.paidChannel())
        && payment.pix() != null
        && payment.pix().txid() != null) {
      Optional<ReceivedPix> pix = Optional.empty();
      try {
        // The Pix side of the same charge, through the Pix door: one bank, two products.
        ResolvedProvider<PixMethodProvider> pixSide =
            providers.resolvePix(payment.merchantId(), payment.environment(), payment.provider());
        pix =
            providers
                .call(
                    payment.id(),
                    "findCharge",
                    pixSide,
                    target -> target.provider().find(target.credentials(), payment.pix().txid()))
                .filter(c -> c.status() == ChargeStatus.COMPLETED)
                .flatMap(c -> c.firstPix());
      } catch (ProviderException e) {
        log.info(
            "could not read the pix of boleto {} of payment {}: {}; completing without its endToEndId",
            payment.boleto().nossoNumero(),
            payment.id(),
            e.code());
      }
      if (pix.isPresent()) {
        paymentService.settle(payment.merchantId(), payment.id(), pix.get(), by);
        return;
      }
    }
    paymentService.settleBoleto(payment.merchantId(), payment.id(), status, by);
  }

  /**
   * An empty answer once is the bank still processing (its 202); a second one (on any later poll,
   * not necessarily the next) is a boleto that does not exist there. The count is the "ignored"
   * events carrying the mark, so it survives restarts and a second worker; counting only
   * consecutive ones would need a reset event on every non-empty answer for no gain.
   */
  private boolean notFound(Payment payment, String nn, EventSource by) {
    long previous =
        payments.events(payment.id()).stream()
            .filter(e -> "ignored".equals(e.type()) && e.payload().contains(NOT_FOUND_MARK))
            .count();
    record(payment.id(), NOT_FOUND_MARK + ": " + nn, by);
    if (previous + 1 >= 2) {
      paymentService.openDivergence(
          payment,
          "NOT_FOUND_AT_BANK",
          "boleto " + nn + " unknown to the bank on " + (previous + 1) + " polls");
    } else {
      log.info("boleto {} of payment {} not at the bank yet", nn, payment.id());
    }
    return pastWindow(payment);
  }

  private void record(String paymentId, String what, EventSource by) {
    transactionTemplate.executeWithoutResult(
        s -> {
          Payment loaded = payments.findById(paymentId).orElseThrow();
          payments.save(loaded, List.of(loaded.recordIgnored(what, by).orElseThrow()));
        });
  }

  /**
   * Two days past the limit date's end (São Paulo): a last-minute payment is credited on the next
   * business day.
   */
  private boolean pastWindow(Payment payment) {
    Instant end =
        BoletoDates.endOfDay(payment.boleto().paymentLimitDate())
            .plus(props.boletoPollGraceAfterLimit());
    return clock.instant().isAfter(end);
  }
}
