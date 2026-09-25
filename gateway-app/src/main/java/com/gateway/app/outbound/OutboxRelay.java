package com.gateway.app.outbound;

import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import com.gateway.payments.PaymentsProperties;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves the payments outbox into webhook-delivery. This is the only caller of {@link MerchantEvents}
 * for payment events: the payments module cannot import the app (ArchUnit {@code nobodyImportsApp}),
 * so it writes outbox rows in the same transaction as the state change, and this relay reads them
 * through the module's own {@link OutboxRepository} interface.
 *
 * <p>The claim is a short transaction ({@code SKIP LOCKED} + lease); the emit runs OUTSIDE it. Held
 * row locks across the intake's own writes would couple the two commits: a rollback here after the
 * intake committed would re-emit, and a slow intake would hold the claim open for every batch.
 * Delivery is therefore at-least-once: a crash between {@code emitRaw} and {@code markSent} emits
 * the row again once its lease expires — the same guarantee merchants already get from retries.
 */
@Component
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int BATCH = 100;

  private final OutboxRepository outbox;
  private final MerchantEvents events;
  private final TransactionTemplate tx;
  private final PaymentsProperties props;

  public OutboxRelay(OutboxRepository outbox, MerchantEvents events, TransactionTemplate tx, PaymentsProperties props) {
    this.outbox = outbox;
    this.events = events;
    this.tx = tx;
    this.props = props;
  }

  @Scheduled(fixedDelayString = "${gateway.payments.outbox-relay-ms:1000}")
  public void relay() {
    List<OutboxMessage> claimed = tx.execute(s -> outbox.claimPending(BATCH, props.outboxLease()));
    if (claimed == null) {
      return;
    }
    // Delivery is ordered per partition key (a payment's events). Once message N of a key fails,
    // emitting N+1 in the same batch would deliver it ahead of N's retry: every later message of
    // that key is released unsent, and the whole key retries together, in order, next tick.
    Set<String> failedKeys = new HashSet<>();
    for (OutboxMessage m : claimed) {
      if (failedKeys.contains(m.partitionKey())) {
        release(m);
        continue;
      }
      try {
        events.emitRaw(m.merchantId(), m.eventType(), m.aggregateId(), m.partitionKey(), m.payload(), eventId(m));
        outbox.markSent(m.id());
      } catch (RuntimeException e) {
        // Released, not left claimed: waiting out the lease would delay this payment's later events too.
        log.warn("outbox message {} ({}) not relayed; released for retry", m.id(), m.eventType(), e);
        failedKeys.add(m.partitionKey());
        release(m);
      }
    }
  }

  static UUID eventId(OutboxMessage m) {
    return UUID.nameUUIDFromBytes(m.id().getBytes(StandardCharsets.UTF_8));
  }

  private void release(OutboxMessage m) {
    try {
      outbox.release(m.id());
    } catch (RuntimeException again) {
      log.warn("could not release outbox message {}; its lease will expire instead", m.id(), again);
    }
  }
}
