package com.gateway.payments.payment;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.jobs.Job;
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
import java.util.HexFormat;
import java.util.List;
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

  public record CreateCharge(
      MerchantId merchantId,
      ProviderEnvironment env,
      Money amount,
      String reference,
      String description,
      String customerDocument,
      Integer expiresInSeconds) {}

  private final PaymentRepository payments;
  private final ReconciliationDivergenceRepository divergences;
  private final JobRepository jobs;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final PaymentsProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PaymentService(
      PaymentRepository payments,
      ReconciliationDivergenceRepository divergences,
      JobRepository jobs,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentsProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.payments = payments;
    this.divergences = divergences;
    this.jobs = jobs;
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
      PaymentEvent ev =
          p.markPending(
              new PixDetails(accepted.txid(), accepted.pixCopiaECola(), accepted.location(), null), clock.instant().plusSeconds(bankExpiry), by);
      Payment saved = payments.save(p, List.of(ev));
      if (!jobs.enqueue(Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock))) {
        log.debug("expire job for payment {} was already queued", saved.id());
      }
      events.emit(saved.merchantId(), "payment.pending", saved);
      return saved;
    });
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
        boolean duplicate =
            p.status() == PaymentStatus.COMPLETED
                && Objects.equals(knownE2e, pix.endToEndId())
                && p.paidAmount() != null
                && p.paidAmount().cents() == pix.amount().cents();
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
  public Settlement settleFromWebhook(MerchantId merchantId, String paymentId, ReceivedPix hinted) {
    Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
    if (found.isEmpty()) {
      return Settlement.UNKNOWN_PAYMENT;
    }
    Payment p = found.get();
    if (p.status() == PaymentStatus.CREATED) {
      // Same as settle: the PENDING write has not landed yet; fail and let the job retry.
      throw new IllegalStateException("pix for payment " + paymentId + " still in " + p.status());
    }
    ProviderGateway.Resolved r = providers.resolve(merchantId, p.environment(), p.provider());
    Optional<Charge> atBank = providers.call(p.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), p.id()));
    Optional<ReceivedPix> confirmed =
        atBank
            .filter(c -> c.status() == ChargeStatus.COMPLETED && c.received() != null)
            .flatMap(c -> c.received().stream().filter(x -> Objects.equals(x.endToEndId(), hinted.endToEndId())).findFirst());
    if (confirmed.isPresent()) {
      return settle(merchantId, paymentId, confirmed.get(), EventSource.PROVIDER_WEBHOOK);
    }
    String bankSays = atBank.map(c -> c.status().name()).orElse("NOT_FOUND");
    tx.executeWithoutResult(s -> {
      Payment loaded = payments.findById(paymentId).orElseThrow();
      payments.save(loaded, List.of(loaded.recordIgnored("unconfirmed webhook: e2eid " + hinted.endToEndId() + ", bank says " + bankSays, EventSource.PROVIDER_WEBHOOK).orElseThrow()));
      openDivergence(
          loaded,
          "UNCONFIRMED_WEBHOOK",
          "webhook said e2eid " + hinted.endToEndId() + " paid " + hinted.amount().cents() + " cents; bank says " + bankSays);
    });
    return Settlement.IGNORED;
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
