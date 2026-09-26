package com.gateway.payments.inbox;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.ProviderWebhookEvent;
import com.gateway.kernel.provider.pix.ReceivedPix;
import com.gateway.kernel.provider.pix.RefundResult;
import com.gateway.payments.inbox.persistence.WebhookInboxRepository;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.refund.RefundService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank gives us 5 s to answer a webhook (NOTES.md). {@link #accept} only stores the raw body
 * and queues a job — nothing that can be slow or can fail on a parsing bug — and {@link #process}
 * does the work later, where a failure is a retry instead of a lost notification.
 */
public class WebhookInboxService {
  private static final Logger log = LoggerFactory.getLogger(WebhookInboxService.class);

  private final WebhookInboxRepository inbox;
  private final JobRepository jobs;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final RefundService refundService;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public WebhookInboxService(
      WebhookInboxRepository inbox,
      JobRepository jobs,
      ProviderGateway providers,
      PaymentService paymentService,
      RefundService refundService,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.inbox = inbox;
    this.jobs = jobs;
    this.providers = providers;
    this.paymentService = paymentService;
    this.refundService = refundService;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public String accept(String provider, MerchantId merchantId, String rawHeaders, byte[] body) {
    String id = Ulid.next();
    transactionTemplate.executeWithoutResult(
        s -> {
          inbox.save(
              new WebhookInboxEntry(
                  id, provider, merchantId, rawHeaders, body, "RECEIVED", null, clock.instant()));
          jobs.enqueue(Job.processWebhook(id, clock));
        });
    return id;
  }

  /**
   * The body is a hint: every Pix and every refund update is confirmed with the bank before it
   * moves anything ({@link PaymentService#settleFromWebhook}, {@link
   * RefundService#confirmFromWebhook}).
   *
   * <p>One transaction per payment (inside {@link PaymentService#settle}), not one for the whole
   * body: a batch of Pix in one webhook must not roll back the ones that succeeded because a later
   * one hit an optimistic-lock conflict. A conflict propagates, the row stays RECEIVED and the job
   * retries — settling is idempotent, so the ones already done become "ignored".
   */
  public void process(String inboxId) {
    WebhookInboxEntry entry = inbox.findById(inboxId).orElse(null);
    if (entry == null || !"RECEIVED".equals(entry.status())) {
      return;
    }
    ProviderWebhookEvent event;
    try {
      event = providers.pixProvider(entry.provider()).parseWebhook(entry.rawBody());
    } catch (RuntimeException e) {
      log.warn("unreadable {} webhook {}", entry.provider(), inboxId, e);
      mark(entry, "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
      return;
    }
    boolean matched = false;
    for (ReceivedPix pix : event.received()) {
      String txid =
          event.txidByEndToEndId() == null ? null : event.txidByEndToEndId().get(pix.endToEndId());
      if (txid == null) {
        continue; // static QR or key transfer: not a charge of ours (NOTES.md)
      }
      PaymentService.Settlement outcome =
          paymentService.settleFromWebhook(entry.merchantId(), txid, pix);
      matched |= outcome != PaymentService.Settlement.UNKNOWN_PAYMENT;
    }
    if (event.refundUpdates() != null) {
      for (RefundResult update : event.refundUpdates()) {
        String e2e =
            event.endToEndIdByRefundId() == null
                ? null
                : event.endToEndIdByRefundId().get(update.refundId());
        matched |= refundService.confirmFromWebhook(entry.merchantId(), e2e, update);
      }
    }
    mark(entry, matched ? "PROCESSED" : "IGNORED", null);
  }

  private void mark(WebhookInboxEntry e, String status, String error) {
    String err = error == null || error.length() <= 500 ? error : error.substring(0, 500);
    transactionTemplate.executeWithoutResult(
        s ->
            inbox.save(
                new WebhookInboxEntry(
                    e.id(),
                    e.provider(),
                    e.merchantId(),
                    e.rawHeaders(),
                    e.rawBody(),
                    status,
                    err,
                    e.receivedAt())));
  }
}
