package com.gateway.payments.payment;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.boleto.Address;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.boleto.Payer;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.payment.boleto.BoletoDates;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.boleto.persistence.BoletoNumberRepository;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.pix.ReceivedPix;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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

  public record CreateCharge(
      MerchantId merchantId,
      ProviderEnvironment env,
      Money amount,
      String reference,
      String description,
      String customerDocument,
      Integer expiresInSeconds) {}

  public record CreateBolecode(
      MerchantId merchantId, ProviderEnvironment env, Money amount, String reference, String description, Payer payer, LocalDate dueDate, Integer paymentLimitDays) {}

  private static final Pattern DIGITS_11_OR_14 = Pattern.compile("\\d{11}|\\d{14}");
  private static final Pattern UF = Pattern.compile("[A-Z]{2}");
  private static final Pattern CEP = Pattern.compile("\\d{8}");
  private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");

  /**
   * A registered boleto needs a complete payer (issue OpenAPI: pessoa and endereco required, every
   * address line required). Checked here, not at the edge: the 422 names the field in the API's own
   * spelling and no row exists yet. Returns the payer with digits-only document and zip.
   */
  static Payer validatePayer(Payer payer) {
    if (payer == null) throw new DomainException("CUSTOMER_REQUIRED", "customer is required for a BOLECODE payment");
    if (payer.name() == null || !HAS_LETTER.matcher(payer.name()).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.name is required");
    String document = payer.document() == null ? "" : payer.document().replaceAll("\\D", "");
    if (!DIGITS_11_OR_14.matcher(document).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.document must be a CPF (11 digits) or CNPJ (14 digits)");
    Address a = payer.address();
    if (a == null) throw new DomainException("CUSTOMER_REQUIRED", "customer.address is required");
    if (a.street() == null || a.street().isBlank()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.street is required");
    if (a.district() == null || a.district().isBlank()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.district is required");
    if (a.city() == null || a.city().isBlank()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.city is required");
    String state = a.state() == null ? null : a.state().trim().toUpperCase(java.util.Locale.ROOT);
    if (state == null || !UF.matcher(state).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.state must be a two-letter UF");
    String zip = a.zip() == null ? "" : a.zip().replaceAll("\\D", "");
    if (!CEP.matcher(zip).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.zip must be 8 digits");
    return new Payer(payer.name(), document, new Address(a.street(), a.district(), a.city(), state, zip));
  }

  private final PaymentRepository payments;
  private final ReconciliationDivergenceRepository divergences;
  private final JobRepository jobs;
  private final BoletoNumberRepository boletoNumbers;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final PaymentsProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PaymentService(
      PaymentRepository payments,
      ReconciliationDivergenceRepository divergences,
      JobRepository jobs,
      BoletoNumberRepository boletoNumbers,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentsProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.payments = payments;
    this.divergences = divergences;
    this.jobs = jobs;
    this.boletoNumbers = boletoNumbers;
    this.providers = providers;
    this.events = events;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public Payment createCharge(CreateCharge cmd) {
    // Fails fast, before a row exists: a merchant with no credential has nothing to clean up.
    ProviderGateway.Resolved r = providers.resolve(cmd.merchantId(), cmd.env(), PROVIDER);
    int expires = cmd.expiresInSeconds() == null ? props.defaultExpiresInSeconds() : cmd.expiresInSeconds();
    Payment payment =
        tx.execute(s -> {
          Payment p =
              Payment.create(
                  cmd.merchantId(), cmd.env(), PROVIDER, cmd.amount(), cmd.reference(), cmd.description(), hashDocument(cmd.customerDocument()), expires, clock);
          return payments.save(p, List.of(p.createdEvent()));
        });

    Charge charge;
    try {
      charge =
          providers.call(
              payment.id(),
              "createCharge",
              r,
              x -> x.provider().createCharge(x.credentials(), payment.id(), cmd.amount(), expires, cmd.customerDocument(), null, cmd.description()));
    } catch (ProviderException e) {
      boolean mayHaveLanded = e.code() == ProviderException.Code.TIMEOUT || e.code() == ProviderException.Code.UNAVAILABLE;
      if (!mayHaveLanded) {
        boolean declined = e.code() == ProviderException.Code.INVALID || e.code() == ProviderException.Code.DECLINED;
        throw fail(payment.id(), declined ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE", e, null);
      }
      // The PUT may have landed: a timeout, and equally a 503/504 from a gateway in front of the
      // bank, says nothing about whether the charge was created. The txid is ours, so we ask
      // before deciding (spec section 3.2) instead of failing a charge the payer may be looking at.
      String code = e.code() == ProviderException.Code.TIMEOUT ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE";
      Optional<Charge> existing;
      try {
        existing = providers.call(payment.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), payment.id()));
      } catch (ProviderException again) {
        throw fail(payment.id(), code, again, r);
      }
      if (existing.isEmpty()) {
        throw fail(payment.id(), code, e, r);
      }
      charge = existing.get();
    }
    return adoptPending(payment.id(), charge, expires, EventSource.API);
  }

  /**
   * CREATED -> PENDING with the bank's charge details, plus the expire job and the outbox row. A
   * payment no longer CREATED is returned as is: the stuck-CREATED sweeper and a slow createCharge
   * can race to adopt the same charge, and the loser must not fail on PENDING -> PENDING.
   */
  Payment adoptPending(String paymentId, Charge accepted, int fallbackExpires, EventSource by) {
    return tx.execute(s -> {
      Payment p = payments.findById(paymentId).orElseThrow();
      if (p.status() != PaymentStatus.CREATED) {
        return p;
      }
      int bankExpiry = accepted.expiresInSeconds() > 0 ? accepted.expiresInSeconds() : fallbackExpires;
      // A Pix txid is ours: we PUT /cob/{payment id}. Storing the echoed one broke settleFromWebhook
      // (it looks up by stored txid) against Itau's sandbox mock, whose PUT /cob always answers
      // txid 7978c0c97ea847e78e8849634473c1f1; production echoes ours, so a mismatch is only logged.
      if (accepted.txid() != null && !accepted.txid().equals(p.id())) {
        log.warn("bank echoed txid {} for payment {}; keeping ours", accepted.txid(), p.id());
      }
      PaymentEvent ev =
          p.markPending(
              new PixDetails(p.id(), accepted.pixCopiaECola(), accepted.location(), null), clock.instant().plusSeconds(bankExpiry), by);
      Payment saved = payments.save(p, List.of(ev));
      if (!jobs.enqueue(Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock))) {
        log.debug("expire job for payment {} was already queued", saved.id());
      }
      events.emit(saved.merchantId(), "payment.pending", saved);
      return saved;
    });
  }

  /**
   * Spec 2026-09-25 §7. Order matters: credentials (incl. beneficiary) and payer are checked before
   * a row exists; the number is reserved in the same transaction as CREATED; the bank call runs
   * outside any transaction; PENDING lands with both sides and both jobs.
   *
   * <p>On TIMEOUT/UNAVAILABLE/202 the query decides: found → adopted; not found → the payment stays
   * CREATED and the caller gets PROVIDER_TIMEOUT. Unlike Pix, it is NOT failed on the spot: the
   * bank's 202 means "operação em andamento", so an empty query a second later proves nothing.
   * ExpirationService.sweepStuckCreated asks again after stuckCreatedAfter and decides.
   */
  public Payment createBolecode(CreateBolecode cmd) {
    ProviderGateway.Resolved r = providers.resolve(cmd.merchantId(), cmd.env(), PROVIDER);
    BoletoProvider boleto = r.boleto().orElseThrow(() -> new DomainException("METHOD_NOT_SUPPORTED", PROVIDER + " has no boleto product"));
    try {
      boleto.requireIssueCredentials(r.credentials());
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CREDENTIALS_INCOMPLETE) {
        throw new DomainException("PROVIDER_CREDENTIALS_MISSING", "the " + PROVIDER + " " + cmd.env() + " credential is missing " + e.providerType());
      }
      throw e;
    }
    Payer payer = validatePayer(cmd.payer());
    LocalDate today = BoletoDates.today(clock);
    LocalDate due = cmd.dueDate() == null ? today.plusDays(props.boletoDefaultDueInDays()) : cmd.dueDate();
    if (due.isBefore(today)) throw new DomainException("INVALID_DUE_DATE", "due_date must be today or later (America/Sao_Paulo)");
    int limitDays = cmd.paymentLimitDays() == null ? props.boletoDefaultPaymentLimitDays() : cmd.paymentLimitDays();
    if (limitDays < 0 || limitDays > props.boletoMaxPaymentLimitDays()) {
      throw new DomainException("INVALID_PAYMENT_LIMIT", "payment_limit_days must be between 0 and " + props.boletoMaxPaymentLimitDays());
    }
    LocalDate limit = due.plusDays(limitDays);

    Payment payment =
        tx.execute(s -> {
          String nossoNumero = boletoNumbers.next(cmd.merchantId());
          BoletoDetails details = new BoletoDetails(nossoNumero, null, null, null, due, limit, null);
          Payment p = Payment.createBolecode(cmd.merchantId(), cmd.env(), PROVIDER, cmd.amount(), cmd.reference(), cmd.description(),
              hashDocument(payer.document()), details, BoletoDates.endOfDay(limit), clock);
          return payments.save(p, List.of(p.createdEvent()));
        });
    String nossoNumero = payment.boleto().nossoNumero();
    BoletoIssueRequest request = new BoletoIssueRequest(nossoNumero, cmd.amount(), due, limit, payer, cmd.description());

    IssuedBoleto issued;
    try {
      issued = providers.call(payment.id(), "issueBoleto", r, x -> boleto.issue(x.credentials(), request));
    } catch (ProviderException e) {
      // CONFLICT on the issue is the bank saying "this nosso número already exists" (ruling R4): the
      // number is ours and reserved before the call, so it can only be our own earlier attempt that
      // landed. Failing it would leave a payable boleto behind a FAILED payment; the query adopts it.
      boolean mayHaveLanded = e.code() == ProviderException.Code.TIMEOUT || e.code() == ProviderException.Code.UNAVAILABLE
          || e.code() == ProviderException.Code.CONFLICT;
      if (!mayHaveLanded) {
        boolean declined = e.code() == ProviderException.Code.INVALID || e.code() == ProviderException.Code.DECLINED;
        throw fail(payment.id(), declined ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE", e, null);
      }
      String code = e.code() == ProviderException.Code.TIMEOUT ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE";
      Optional<BoletoStatus> existing;
      try {
        existing = providers.call(payment.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nossoNumero));
      } catch (ProviderException again) {
        throw ProviderErrors.toDomain(code, again, log, "issueBoleto", payment.id());
      }
      if (existing.isEmpty()) {
        log.warn("boleto {} for payment {} not at the bank after {}; left CREATED for the sweeper", nossoNumero, payment.id(), e.code());
        throw ProviderErrors.toDomain(code, e, log, "issueBoleto", payment.id());
      }
      try {
        return adoptBolecodeFromStatus(payment.id(), r, existing.get(), EventSource.API);
      } catch (ProviderException again) {
        // The boleto exists but GET /cob itself failed (bank unreachable), so nothing is known about
        // the txid yet. This is not the "bank does not know the txid" case: that one is adopted and
        // flagged PIX_TXID_UNCONFIRMED inside adoptBolecodeFromStatus (ruling R1). Here the payment
        // stays CREATED and the sweeper asks again after stuckCreatedAfter. Translated here, not inside
        // adoptBolecodeFromStatus, so the sweeper still sees the raw failure and retries.
        throw ProviderErrors.toDomain(code, again, log, "confirmBoletoTxid", payment.id());
      }
    }
    return adoptPendingBolecode(payment.id(), issued, EventSource.API);
  }

  /** CREATED -> PENDING with both sides, the expire job (limit date's end of day + grace) and the poll job (+boletoPollEvery), plus the outbox row. */
  Payment adoptPendingBolecode(String paymentId, IssuedBoleto issued, EventSource by) {
    return adoptPendingBolecode(paymentId, issued, by, null);
  }

  /** {@code unconfirmedDetail} non-null opens PIX_TXID_UNCONFIRMED in the same transaction, and only if this call made the transition. */
  private Payment adoptPendingBolecode(String paymentId, IssuedBoleto issued, EventSource by, String unconfirmedDetail) {
    return tx.execute(s -> {
      Payment p = payments.findById(paymentId).orElseThrow();
      if (p.status() != PaymentStatus.CREATED) {
        // The sweeper and a slow create may race to adopt the same boleto; the loser must not fail,
        // and must not flag a txid it did not adopt: the winner's own confirmation is what counts.
        return p;
      }
      BoletoDetails details = p.boleto().withIssued(issued.idBoletoIndividual(), issued.linhaDigitavel(), issued.codigoBarras(), issued.paymentLimitDate());
      PixDetails pix = new PixDetails(issued.pixTxid(), issued.pixCopiaECola(), null, null);
      Payment saved = payments.save(p, List.of(p.markPendingBolecode(pix, details, BoletoDates.endOfDay(details.paymentLimitDate()), by)));
      if (!jobs.enqueue(Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock))) {
        log.debug("expire job for payment {} was already queued", saved.id());
      }
      if (!jobs.enqueue(Job.pollBoleto(saved.id(), clock.instant().plus(props.boletoPollEvery()), clock))) {
        log.debug("poll job for payment {} was already queued", saved.id());
      }
      events.emit(saved.merchantId(), "payment.pending", saved);
      if (unconfirmedDetail != null) {
        openDivergence(saved, "PIX_TXID_UNCONFIRMED", unconfirmedDetail);
      }
      return saved;
    });
  }

  /**
   * The issue's answer was lost; the query has the boleto's identity but not the Pix side. The txid
   * follows the bank's documented formula (BoletoProvider.pixTxidFor) and is confirmed with
   * GET /cob/{txid}, which also yields the EMV; if the bank does not know that txid, the query's
   * own qrcode_pix.emv is used and a divergence records that the formula did not match.
   */
  Payment adoptBolecodeFromStatus(String paymentId, ProviderGateway.Resolved r, BoletoStatus status, EventSource by) {
    Payment p = payments.findById(paymentId).orElseThrow();
    String nossoNumero = p.boleto().nossoNumero();
    String txid = r.boleto().orElseThrow().pixTxidFor(r.credentials(), nossoNumero);
    Optional<Charge> charge = providers.call(paymentId, "findCharge", r, x -> x.provider().findCharge(x.credentials(), txid));
    String emv = charge.map(Charge::pixCopiaECola).orElse(status.pixCopiaECola());
    // Ruling R1: an unconfirmed txid is still ADOPTED, and flagged. Refusing would leave a boleto the
    // bank issued in CREATED, and the sweeper would later fail it while it is payable; the poll
    // settles by nosso número, so the txid only matters for the Pix webhook match and refunds.
    String unconfirmed = charge.isEmpty() ? "GET /cob/" + txid + " empty while adopting boleto " + nossoNumero + " from the query" : null;
    return adoptPendingBolecode(paymentId,
        new IssuedBoleto(status.idBoletoIndividual(), status.linhaDigitavel(), status.codigoBarras(), status.paymentLimitDate(), txid, emv, null), by, unconfirmed);
  }

  /**
   * Marks the payment FAILED (with its event and outbox row) and returns the exception to throw.
   * With {@code r} set (the charge's fate is unknown), also asks the bank to remove the charge,
   * best effort: if the PUT did land after all, a QR we reported as failed must not stay payable.
   */
  private DomainException fail(String paymentId, String code, ProviderException cause, ProviderGateway.Resolved r) {
    if (r != null) {
      try {
        providers.run(paymentId, "cancelCharge", r, x -> x.provider().cancelCharge(x.credentials(), paymentId));
      } catch (RuntimeException ignored) {
        // NOT_FOUND is the expected answer; anything else is left to reconciliation.
      }
    }
    markFailed(paymentId, code, EventSource.API);
    return ProviderErrors.toDomain(code, cause, log, "createCharge", paymentId);
  }

  /** CREATED -> FAILED with event and outbox row; a payment no longer CREATED is left alone. */
  void markFailed(String paymentId, String code, EventSource by) {
    tx.executeWithoutResult(s -> {
      Payment p = payments.findById(paymentId).orElseThrow();
      if (p.status() != PaymentStatus.CREATED) {
        return;
      }
      Payment saved = payments.save(p, List.of(p.markFailed(code, by)));
      events.emit(saved.merchantId(), "payment.failed", saved);
    });
  }

  public Payment get(MerchantId merchantId, String id) {
    return payments.findByMerchantAndId(merchantId, id).orElseThrow(() -> new NotFoundException("payment", id));
  }

  public List<Payment> list(MerchantId merchantId, int limit, String cursor) {
    return payments.listByMerchant(merchantId, limit, cursor);
  }

  /**
   * The merchant's own order reference, newest first. What a client checks after a 409 IN_PROGRESS
   * on a create: whether the interrupted request left a payment behind before retrying with a new key.
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
      throw new DomainException("INVALID_STATE", "only a pending payment can be canceled, this one is " + current.status());
    }
    ProviderGateway.Resolved r = providers.resolve(merchantId, current.environment(), current.provider());
    if (current.method() == PaymentMethod.BOLECODE) {
      cancelBoletoAtBank(current, r);
    } else {
      try {
        providers.run(id, "cancelCharge", r, x -> x.provider().cancelCharge(x.credentials(), id));
      } catch (ProviderException e) {
        // The bank refuses to remove a charge that is no longer ATIVA — most likely it was just paid
        // and the webhook is on its way. Cancelling here would contradict the bank.
        if (e.code() == ProviderException.Code.INVALID) {
          throw new DomainException("INVALID_STATE", "the bank no longer accepts cancelling this charge");
        }
        throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "cancelCharge", id);
      }
    }
    return tx.execute(s -> {
      Payment p = payments.findByMerchantAndId(merchantId, id).orElseThrow();
      if (p.status() != PaymentStatus.PENDING) {
        throw new DomainException("INVALID_STATE", "payment changed to " + p.status() + " while cancelling");
      }
      Payment saved = payments.save(p, List.of(p.markCanceled(EventSource.API)));
      events.emit(saved.merchantId(), "payment.canceled", saved);
      return saved;
    });
  }

  /**
   * The bank first (spec §7): a barcode payment is only visible through the query, and a baixa on
   * a paid boleto would contradict money that already arrived. Paid → the payment completes here
   * and the caller gets ALREADY_PAID (a 409 at the edge). Open → baixa; the bank's CONFLICT means
   * it was paid between the two calls, so the query is asked once more and decides. The QR dies
   * with the boleto (product docs); if it does not, expiration covers it.
   */
  private void cancelBoletoAtBank(Payment p, ProviderGateway.Resolved r) {
    BoletoProvider boleto = r.boleto().orElseThrow(() -> new DomainException("METHOD_NOT_SUPPORTED", PROVIDER + " has no boleto product"));
    String nn = p.boleto().nossoNumero();
    Optional<BoletoStatus> before = findBoletoForCancel(p, r, boleto, nn);
    if (before.isPresent() && before.get().paid()) {
      throw alreadyPaid(p, before.get());
    }
    try {
      providers.run(p.id(), "cancelBoleto", r, x -> boleto.cancel(x.credentials(), nn));
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CONFLICT) {
        Optional<BoletoStatus> after = findBoletoForCancel(p, r, boleto, nn);
        if (after.isPresent() && after.get().paid()) {
          throw alreadyPaid(p, after.get());
        }
        throw new DomainException("INVALID_STATE", "the bank no longer accepts cancelling this boleto");
      }
      if (e.code() == ProviderException.Code.NOT_FOUND) {
        return; // nothing to invalidate at the bank; the gateway side is still canceled below
      }
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "cancelBoleto", p.id());
    }
  }

  /**
   * A request caller: a timeout or a 503 on the query must reach the merchant as PROVIDER_*, not as
   * a raw ProviderException (a 500), and the payment stays PENDING because nothing was decided.
   */
  private Optional<BoletoStatus> findBoletoForCancel(Payment p, ProviderGateway.Resolved r, BoletoProvider boleto, String nn) {
    try {
      return providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
    } catch (ProviderException e) {
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "findBoleto", p.id());
    }
  }

  private DomainException alreadyPaid(Payment p, BoletoStatus status) {
    if (settleBoleto(p.merchantId(), p.id(), status, EventSource.RECONCILIATION) == Settlement.COMPLETED) {
      return new DomainException("ALREADY_PAID", "the bank shows this boleto paid; the payment is now COMPLETED");
    }
    // settleBoleto refused to complete (an amount mismatch, say) and opened a divergence. The cancel
    // is still refused because the bank holds money for it, but claiming COMPLETED would be false.
    return new DomainException("ALREADY_PAID", "the bank reports a payment for this boleto that is under review (divergence opened); the payment status is unchanged");
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
  public Settlement settle(MerchantId merchantId, String paymentId, ReceivedPix pix, EventSource by) {
    return tx.execute(s -> {
      Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
      if (found.isEmpty()) {
        return Settlement.UNKNOWN_PAYMENT;
      }
      Payment p = found.get();
      if ((p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED) && pix.amount().cents() != p.amount().cents()) {
        // A charge has a fixed amount; a Pix of another amount is not "the payment". Completing it
        // would tell the merchant to ship an order of 159.90 against 1.00. No transition, nothing to
        // the merchant; a human decides.
        payments.save(p, List.of(p.recordIgnored("pix " + pix.endToEndId() + " of " + pix.amount().cents() + " cents, charge is " + p.amount().cents(), by).orElseThrow()));
        openDivergence(p, "AMOUNT_MISMATCH", "pix " + pix.endToEndId() + " paid " + pix.amount().cents() + " cents, charge is " + p.amount().cents());
        return Settlement.IGNORED;
      }
      if (p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED) {
        Payment saved = payments.save(p, List.of(p.markCompleted(pix.endToEndId(), pix.amount(), pix.paidAt(), by)));
        events.emit(saved.merchantId(), "payment.completed", saved);
        return Settlement.COMPLETED;
      }
      if (p.status().terminal()) {
        String knownE2e = p.pix() == null ? null : p.pix().endToEndId();
        boolean sameAmount = p.paidAmount() != null && p.paidAmount().cents() == pix.amount().cents();
        // A Bolecode the boleto query completed via PIX without an endToEndId (GET /cob unreachable
        // at that moment) has no e2eid to compare: its QR is its only Pix side, so a Pix of the same
        // amount is the one the query already saw, not a second payment.
        boolean completedByQueryViaPix = p.boleto() != null && p.boleto().paidVia() == PaidVia.PIX && knownE2e == null;
        boolean duplicate =
            p.status() == PaymentStatus.COMPLETED && sameAmount && (Objects.equals(knownE2e, pix.endToEndId()) || completedByQueryViaPix);
        String what = duplicate ? "duplicate e2eid " + pix.endToEndId() : "pix " + pix.endToEndId() + " on a " + p.status() + " payment";
        payments.save(p, List.of(p.recordIgnored(what, by).orElseThrow()));
        if (!duplicate) {
          // Money arrived that the merchant will never hear about (FAILED/CANCELED), or a second,
          // different Pix on a COMPLETED charge. No legal transition moves the payment and nothing
          // goes to the merchant; a human decides (refund the payer, or reopen the order).
          openDivergence(p, "PIX_RECEIVED", "paid at bank while " + p.status() + ": e2eid " + pix.endToEndId() + ", " + pix.amount().cents() + " cents");
        }
        return Settlement.IGNORED;
      }
      // CREATED: the bank cannot have been paid for a charge it has not answered yet; failing makes
      // the caller's job retry once the PENDING write lands.
      throw new IllegalStateException("pix for payment " + paymentId + " still in " + p.status());
    });
  }

  /**
   * A received Pix announced by the bank's webhook. The webhook is a hint, not the truth: anyone who
   * can reach the endpoint with the right token could POST a body saying "paid", and the merchant
   * would ship goods on our {@code payment.completed}. So the bank is asked ({@code GET /cob/{txid}})
   * and the payment completes only with what the BANK reports: the charge CONCLUIDA with a Pix of the
   * same endToEndId. The amount is then checked by {@link #settle} against the charge's own.
   *
   * <p>Not confirmed: an "ignored" event and an {@code UNCONFIRMED_WEBHOOK} divergence, nothing to
   * the merchant. The bank unreachable: the exception propagates and the inbox job retries.
   */
  public Settlement settleFromWebhook(MerchantId merchantId, String txid, ReceivedPix hinted) {
    // By txid, not by id: a Bolecode's txid is the bank's BL..., and the webhook only knows the txid.
    Optional<Payment> found = payments.findByMerchantAndTxid(merchantId, PROVIDER, txid);
    if (found.isEmpty()) {
      return Settlement.UNKNOWN_PAYMENT;
    }
    Payment p = found.get();
    if (p.status() == PaymentStatus.CREATED) {
      throw new IllegalStateException("pix for payment " + p.id() + " still in " + p.status());
    }
    ProviderGateway.Resolved r = providers.resolve(merchantId, p.environment(), p.provider());
    Optional<Charge> atBank = providers.call(p.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), p.pix().txid()));
    Optional<ReceivedPix> confirmed =
        atBank
            .filter(c -> c.status() == ChargeStatus.COMPLETED && c.received() != null)
            .flatMap(c -> c.received().stream().filter(x -> Objects.equals(x.endToEndId(), hinted.endToEndId())).findFirst());
    if (confirmed.isPresent()) {
      return settle(merchantId, p.id(), confirmed.get(), EventSource.PROVIDER_WEBHOOK);
    }
    String bankSays = atBank.map(c -> c.status().name()).orElse("NOT_FOUND");
    tx.executeWithoutResult(s -> {
      Payment loaded = payments.findById(p.id()).orElseThrow();
      payments.save(loaded, List.of(loaded.recordIgnored("unconfirmed webhook: e2eid " + hinted.endToEndId() + ", bank says " + bankSays, EventSource.PROVIDER_WEBHOOK).orElseThrow()));
      openDivergence(loaded, "UNCONFIRMED_WEBHOOK", "webhook said e2eid " + hinted.endToEndId() + " paid " + hinted.amount().cents() + " cents; bank says " + bankSays);
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
  public Settlement settleBoleto(MerchantId merchantId, String paymentId, BoletoStatus status, EventSource by) {
    return tx.execute(s -> {
      Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
      if (found.isEmpty()) {
        return Settlement.UNKNOWN_PAYMENT;
      }
      Payment p = found.get();
      if (p.method() != PaymentMethod.BOLECODE || p.boleto() == null) {
        throw new IllegalStateException("settleBoleto on a " + p.method() + " payment " + paymentId);
      }
      String nn = p.boleto().nossoNumero();
      long paidCents = status.paidAmount() == null ? -1 : status.paidAmount().cents();
      if (p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED) {
        if (paidCents != p.amount().cents()) {
          payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid " + paidCents + " cents, charge is " + p.amount().cents(), by).orElseThrow()));
          openDivergence(p, "AMOUNT_MISMATCH", "boleto " + nn + " paid " + paidCents + " cents at the bank, charge is " + p.amount().cents());
          return Settlement.IGNORED;
        }
        Instant paidAt = status.paidAt() == null ? clock.instant() : status.paidAt();
        // Ruling R3: a Pix channel means the payer used the QR, and a Pix settlement is refundable at
        // the bank while a barcode one is not. Recording BOLETO here would lock the merchant out of a
        // refund they are owed. The query has no endToEndId; BoletoPollingService asks GET /cob for it
        // first, so reaching this with a Pix channel means it stays unknown.
        PaymentEvent completion = isPixChannel(status.paidChannel())
            ? p.markCompleted(null, status.paidAmount(), paidAt, by)
            : p.markCompletedByBoleto(status.paidAmount(), paidAt, status.paidChannel(), by);
        Payment saved = payments.save(p, List.of(completion));
        events.emit(saved.merchantId(), "payment.completed", saved);
        return Settlement.COMPLETED;
      }
      if (p.status() == PaymentStatus.COMPLETED) {
        if (p.boleto().paidVia() == PaidVia.BOLETO) {
          payments.save(p, List.of(p.recordIgnored("boleto " + nn + " already settled", by).orElseThrow()));
          return Settlement.IGNORED;
        }
        // Paying the QR of a Bolecode settles the boleto at the bank too, so "paid" after a Pix
        // completion is normally the same money. Only a channel that is not Pix is a second payment.
        // The channel codes are unconfirmed until the sandbox smoke; a missing one is logged, not flagged.
        String channel = status.paidChannel();
        if (channel == null || channel.isBlank()) {
          log.warn("boleto {} of payment {} paid at the bank without a payment channel; assumed its own pix", nn, paymentId);
          payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid at the bank, no channel, on a payment completed via PIX", by).orElseThrow()));
          return Settlement.IGNORED;
        }
        if (isPixChannel(channel)) {
          payments.save(p, List.of(p.recordIgnored("boleto " + nn + " settled by its own pix (" + channel + ")", by).orElseThrow()));
          return Settlement.IGNORED;
        }
        payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid at the bank via " + channel + " on a payment completed via PIX", by).orElseThrow()));
        openDivergence(p, "DOUBLE_PAYMENT", "paid via PIX (e2eid " + (p.pix() == null ? null : p.pix().endToEndId()) + ") and boleto " + nn + " paid " + paidCents + " cents via " + channel);
        return Settlement.IGNORED;
      }
      if (p.status().terminal()) {
        // Money arrived for a charge the merchant will never hear about again (FAILED/CANCELED).
        payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid at the bank while " + p.status(), by).orElseThrow()));
        openDivergence(p, "BOLETO_PAID", "boleto " + nn + " paid " + paidCents + " cents at the bank while " + p.status());
        return Settlement.IGNORED;
      }
      throw new IllegalStateException("boleto settlement for payment " + paymentId + " still in " + p.status());
    });
  }

  /** Whether the bank's payment channel is Pix; null or blank is not (the caller decides what an absent channel means). */
  public static boolean isPixChannel(String channel) {
    if (channel == null || channel.isBlank()) return false;
    String plain = java.text.Normalizer.normalize(channel, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    return plain.contains("pix");
  }

  /**
   * Opens a divergence unless the same (payment, provider status) one is already OPEN. Reconciliation
   * runs every 15 minutes over a 48 h window: without the check one mismatch would open ~190
   * identical divergences before anyone looked at the first. The check is the database's (partial
   * unique index, V201), not a scan of every OPEN row.
   */
  public boolean openDivergence(Payment p, String providerStatus, String detail) {
    String trimmed = detail.length() <= 500 ? detail : detail.substring(0, 500);
    return divergences.openIfAbsent(new ReconciliationDivergence(Ulid.next(), p.id(), p.status().name(), providerStatus, trimmed, "OPEN", clock.instant()));
  }

  /**
   * SHA-256 hex of the document's digits only, so "123.456.789-09" and "12345678909" search as the
   * same customer. The document itself is never stored in plan B.
   */
  static String hashDocument(String document) {
    if (document == null) return null;
    String digits = document.replaceAll("\\D", "");
    if (digits.isEmpty()) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(digits.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}


