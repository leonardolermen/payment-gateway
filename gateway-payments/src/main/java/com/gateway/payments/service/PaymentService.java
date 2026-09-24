package com.gateway.payments.service;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.Charge;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.domain.EventSource;
import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentEvent;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.domain.PixDetails;
import com.gateway.payments.repository.JobRepository;
import com.gateway.payments.repository.PaymentRepository;
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
  static final String PROVIDER = "ITAU";

  public record CreateCharge(
      MerchantId merchantId,
      ProviderEnvironment env,
      Money amount,
      String reference,
      String description,
      String customerDocument,
      Integer expiresInSeconds) {}

  private final PaymentRepository payments;
  private final JobRepository jobs;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final PaymentsProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PaymentService(
      PaymentRepository payments,
      JobRepository jobs,
      ProviderGateway providers,
      PaymentEvents events,
      PaymentsProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.payments = payments;
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
      if (e.code() != ProviderException.Code.TIMEOUT) {
        boolean declined = e.code() == ProviderException.Code.INVALID || e.code() == ProviderException.Code.DECLINED;
        throw fail(payment.id(), declined ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE", e);
      }
      // The PUT may have landed. The txid is ours, so we can ask before deciding (spec section 3.2)
      // instead of failing a charge the payer may already be looking at.
      Optional<Charge> existing;
      try {
        existing = providers.call(payment.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), payment.id()));
      } catch (ProviderException again) {
        throw fail(payment.id(), "PROVIDER_TIMEOUT", again);
      }
      if (existing.isEmpty()) {
        throw fail(payment.id(), "PROVIDER_TIMEOUT", e);
      }
      charge = existing.get();
    }

    Charge accepted = charge;
    return tx.execute(s -> {
      Payment p = payments.findById(payment.id()).orElseThrow();
      int bankExpiry = accepted.expiresInSeconds() > 0 ? accepted.expiresInSeconds() : expires;
      PaymentEvent ev =
          p.markPending(new PixDetails(accepted.txid(), accepted.pixCopiaECola(), accepted.location(), null), clock.instant().plusSeconds(bankExpiry));
      Payment saved = payments.save(p, List.of(ev));
      if (!jobs.enqueue(Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock))) {
        log.debug("expire job for payment {} was already queued", saved.id());
      }
      events.emit(saved.merchantId(), "payment.pending", saved);
      return saved;
    });
  }

  /** Marks the payment FAILED (with its event and outbox row) and returns the exception to throw. */
  private DomainException fail(String paymentId, String code, ProviderException cause) {
    tx.executeWithoutResult(s -> {
      Payment p = payments.findById(paymentId).orElseThrow();
      Payment saved = payments.save(p, List.of(p.markFailed(code, EventSource.API)));
      events.emit(saved.merchantId(), "payment.failed", saved);
    });
    DomainException e = new DomainException(code, cause.getMessage());
    e.initCause(cause);
    return e;
  }

  public Payment get(MerchantId merchantId, String id) {
    return payments.findByMerchantAndId(merchantId, id).orElseThrow(() -> new NotFoundException("payment", id));
  }

  public List<Payment> list(MerchantId merchantId, int limit, String cursor) {
    return payments.listByMerchant(merchantId, limit, cursor);
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
      throw new DomainException("PROVIDER_UNAVAILABLE", e.getMessage());
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
  public Settlement settle(MerchantId merchantId, String paymentId, com.gateway.kernel.provider.ReceivedPix pix, EventSource by) {
    return tx.execute(s -> {
      Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
      if (found.isEmpty()) {
        return Settlement.UNKNOWN_PAYMENT;
      }
      Payment p = found.get();
      if (p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED) {
        Payment saved = payments.save(p, List.of(p.markCompleted(pix.endToEndId(), pix.amount(), pix.paidAt(), by)));
        events.emit(saved.merchantId(), "payment.completed", saved);
        return Settlement.COMPLETED;
      }
      if (p.status().terminal()) {
        String what = pix.endToEndId().equals(p.pix().endToEndId()) ? "duplicate e2eid " + pix.endToEndId() : "pix " + pix.endToEndId() + " on a " + p.status() + " payment";
        payments.save(p, List.of(p.recordIgnored(what, by).orElseThrow()));
        return Settlement.IGNORED;
      }
      // CREATED: the bank cannot have been paid for a charge it has not answered yet; failing makes
      // the caller's job retry once the PENDING write lands.
      throw new IllegalStateException("pix for payment " + paymentId + " still in " + p.status());
    });
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
