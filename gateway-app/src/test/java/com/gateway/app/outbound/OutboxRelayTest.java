package com.gateway.app.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayTest {
  private static final MerchantId MERCHANT = MerchantId.next();

  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final MerchantEvents events = mock(MerchantEvents.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final OutboxRelay relay =
      new OutboxRelay(outbox, events, tx, PaymentsProperties.defaults());

  @SuppressWarnings("unchecked")
  private void claims(OutboxMessage... rows) {
    when(tx.execute(any()))
        .thenAnswer(
            inv ->
                ((TransactionCallback<Object>) inv.getArgument(0))
                    .doInTransaction(new SimpleTransactionStatus()));
    when(outbox.claimPending(eq(100), any())).thenReturn(List.of(rows));
  }

  private static OutboxMessage row(String id, String partitionKey, String type) {
    return new OutboxMessage(
        id, MERCHANT, partitionKey, partitionKey, type, "{}", "PENDING", null, Instant.now());
  }

  @Test
  void aFailedMessageHoldsBackTheRestOfItsPartitionButNotOthers() {
    OutboxMessage first = row("m1", "pay_X", "payment.pending");
    OutboxMessage second = row("m2", "pay_X", "payment.completed");
    OutboxMessage other = row("m3", "pay_Y", "payment.pending");
    claims(first, second, other);
    doThrow(new IllegalStateException("intake down"))
        .when(events)
        .emitRaw(any(), eq("payment.pending"), eq("pay_X"), eq("pay_X"), any(), any());

    relay.relay();

    verify(outbox).release("m1");
    verify(outbox).release("m2");
    verify(events, never()).emitRaw(any(), eq("payment.completed"), any(), any(), any(), any());
    verify(outbox, never()).markSent("m2");
    verify(outbox).markSent("m3");
  }

  @Test
  void theSameRowAlwaysGetsTheSameEventId() {
    OutboxMessage m = row("01ROWID", "pay_Z", "payment.pending");
    claims(m);
    relay.relay();
    relay.relay();

    ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
    verify(events, org.mockito.Mockito.times(2))
        .emitRaw(any(), any(), any(), any(), any(), ids.capture());
    assertThat(ids.getAllValues()).hasSize(2).containsOnly(OutboxRelay.eventId(m));
  }
}
