package com.gateway.payments.payment;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.create.BolecodeFromQuery;
import com.gateway.payments.payment.create.CreateFailures;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.gateway.payments.payment.create.PaymentFlows;
import com.gateway.payments.payment.create.PendingAdoption;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating and cancelling charges. Deliberately NOT {@code @Transactional}: the bank call runs
 * outside any transaction (a 30 s bank timeout must not hold a pooled connection and row locks for
 * 30 s), and {@link TransactionTemplate} wraps only the writes before and after it.
 */
public class PaymentService {
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  /** Plan B has one bank. The provider is resolved by name so a second one is a config change. */
  public static final String PROVIDER = "ITAU";

  private final PaymentRepository payments;
  private final Divergences divergences;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final PaymentFlows flows;
  private final PendingAdoption adoption;
  private final BolecodeFromQuery bolecodeFromQuery;
  private final CreateFailures failures;
  private final PaymentsProperties props;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public PaymentService(
      PaymentRepository payments,
      Divergences divergences,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentFlows flows,
      PendingAdoption adoption,
      BolecodeFromQuery bolecodeFromQuery,
      CreateFailures failures,
      PaymentsProperties props,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.payments = payments;
    this.divergences = divergences;
    this.providers = providers;
    this.events = events;
    this.flows = flows;
    this.adoption = adoption;
    this.bolecodeFromQuery = bolecodeFromQuery;
    this.failures = failures;
    this.props = props;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
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

  public Payment get(MerchantId merchantId, String id) {
    return payments
        .findByMerchantAndId(merchantId, id)
        .orElseThrow(() -> new NotFoundException("payment", id));
  }

  public List<Payment> list(MerchantId merchantId, int limit, String cursor) {
    return payments.listByMerchant(merchantId, limit, cursor);
  }

  /**
   * The merchant's own order reference, newest first. What a client checks after a 409 IN_PROGRESS
   * on a create: whether the interrupted request left a payment behind before retrying with a new
   * key.
   */
  public List<Payment> listByReference(MerchantId merchantId, String reference, int limit) {
    return payments.listByMerchantAndReference(merchantId, reference, limit);
  }

  public List<PaymentEvent> events(MerchantId merchantId, String id) {
    return payments.events(get(merchantId, id).id());
  }

  public Payment cancel(MerchantId merchantId, String id) {
    Payment current = get(merchantId, id);
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
    return transactionTemplate.execute(
        s -> {
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
    String nn = payment.boleto().nossoNumero();
    Optional<BoletoStatus> before = findBoletoForCancel(payment, resolved, nn);
    if (before.isPresent() && before.get().paid()) {
      throw alreadyPaid(payment, before.get());
    }
    try {
      providers.run(
          payment.id(),
          "cancelBoleto",
          resolved,
          target -> target.provider().cancel(target.credentials(), nn));
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CONFLICT) {
        Optional<BoletoStatus> after = findBoletoForCancel(payment, resolved, nn);
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
      Payment payment, ResolvedProvider<BoletoMethodProvider> resolved, String nn) {
    try {
      return providers.call(
          payment.id(),
          "findBoleto",
          resolved,
          target -> target.provider().find(target.credentials(), nn));
    } catch (ProviderException e) {
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "findBoleto", payment.id());
    }
  }

  private DomainException alreadyPaid(Payment payment, BoletoStatus status) {
    if (settleBoleto(payment.merchantId(), payment.id(), status, EventSource.RECONCILIATION)
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

  public enum Settlement {
    COMPLETED,
    IGNORED,
    UNKNOWN_PAYMENT
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
    return transactionTemplate.execute(
        s -> {
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
            openDivergence(
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
              openDivergence(
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
    Optional<Payment> found = payments.findByMerchantAndTxid(merchantId, PROVIDER, txid);
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
            .filter(c -> c.status() == ChargeStatus.COMPLETED && c.received() != null)
            .flatMap(
                c ->
                    c.received().stream()
                        .filter(x -> Objects.equals(x.endToEndId(), hinted.endToEndId()))
                        .findFirst());
    if (confirmed.isPresent()) {
      return settle(merchantId, payment.id(), confirmed.get(), EventSource.PROVIDER_WEBHOOK);
    }
    String bankSays = atBank.map(c -> c.status().name()).orElse("NOT_FOUND");
    transactionTemplate.executeWithoutResult(
        s -> {
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
          openDivergence(
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
    return transactionTemplate.execute(
        s -> {
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
              openDivergence(
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
            openDivergence(
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
            openDivergence(
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
