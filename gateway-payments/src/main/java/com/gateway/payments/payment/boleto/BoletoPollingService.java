package com.gateway.payments.payment.boleto;

import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentMethod;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
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
  private final TransactionTemplate tx;
  private final Clock clock;

  public BoletoPollingService(
      PaymentRepository payments, ProviderGateway providers, PaymentService paymentService, PaymentsProperties props, TransactionTemplate tx, Clock clock) {
    this.payments = payments;
    this.providers = providers;
    this.paymentService = paymentService;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public boolean check(String paymentId, EventSource by) {
    Payment p = payments.findById(paymentId).orElse(null);
    if (p == null || p.method() != PaymentMethod.BOLECODE || p.boleto() == null) {
      return true;
    }
    if (p.status() == PaymentStatus.CANCELED || p.status() == PaymentStatus.FAILED || p.status() == PaymentStatus.CREATED) {
      return true; // CREATED is the stuck-CREATED sweeper's business, not the poll's
    }
    ProviderGateway.Resolved r = providers.resolve(p.merchantId(), p.environment(), p.provider());
    BoletoProvider boleto = r.boleto().orElseThrow();
    String nn = p.boleto().nossoNumero();
    Optional<BoletoStatus> atBank = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
    if (atBank.isEmpty()) {
      return notFound(p, nn, by);
    }
    BoletoStatus status = atBank.get();
    if (p.status() == PaymentStatus.COMPLETED) {
      if (status.paid()) {
        paymentService.settleBoleto(p.merchantId(), p.id(), status, by); // duplicate or DOUBLE_PAYMENT, decided there
      } else {
        record(p.id(), "poll after completion: bank says " + status.situation(), by);
      }
      return true;
    }
    return switch (status.situation()) {
      case OPEN, AWAITING_CREDIT -> pastWindow(p);
      case PAID, SETTLED, CREDITED -> {
        paymentService.settleBoleto(p.merchantId(), p.id(), status, by);
        yield true;
      }
      case PAYMENT_REJECTED -> {
        // The bank refused a payment attempt (wrong amount, closed account, ...); the boleto is still open for the payer.
        paymentService.openDivergence(p, "BOLETO_REJECTED", "bank rejected a payment of boleto " + nn + "; still open for the payer");
        yield pastWindow(p);
      }
      case CANCELED -> {
        // Someone did a baixa outside the gateway (bankline, another system). The state is not moved: the merchant did not ask for it.
        paymentService.openDivergence(p, "CANCELED_AT_BANK", "boleto " + nn + " baixado at the bank while the gateway has " + p.status());
        yield true;
      }
    };
  }

  /**
   * An empty answer once is the bank still processing (its 202); twice in a row is a boleto that
   * does not exist there. The count is the "ignored" events carrying the mark, so it survives
   * restarts and a second worker.
   */
  private boolean notFound(Payment p, String nn, EventSource by) {
    long previous = payments.events(p.id()).stream().filter(e -> "ignored".equals(e.type()) && e.payload().contains(NOT_FOUND_MARK)).count();
    record(p.id(), NOT_FOUND_MARK + ": " + nn, by);
    if (previous + 1 >= 2) {
      paymentService.openDivergence(p, "NOT_FOUND_AT_BANK", "boleto " + nn + " unknown to the bank on " + (previous + 1) + " consecutive polls");
    } else {
      log.info("boleto {} of payment {} not at the bank yet", nn, p.id());
    }
    return pastWindow(p);
  }

  private void record(String paymentId, String what, EventSource by) {
    tx.executeWithoutResult(s -> {
      Payment loaded = payments.findById(paymentId).orElseThrow();
      payments.save(loaded, List.of(loaded.recordIgnored(what, by).orElseThrow()));
    });
  }

  /** Two days past the limit date's end (São Paulo): a last-minute payment is credited on the next business day. */
  private boolean pastWindow(Payment p) {
    Instant end = BoletoDates.endOfDay(p.boleto().paymentLimitDate()).plus(props.boletoPollGraceAfterLimit());
    return clock.instant().isAfter(end);
  }
}
