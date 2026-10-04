package com.gateway.app.outbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.outbox.OutboxListener;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayListenersTest {
  OutboxRepository outbox = mock(OutboxRepository.class);
  MerchantEvents events = mock(MerchantEvents.class);
  TransactionTemplate template = mock(TransactionTemplate.class);
  OutboxListener listener = mock(OutboxListener.class);

  OutboxMessage message =
      new OutboxMessage(
          "01MSG",
          MerchantId.next(),
          "pay-1",
          "pay-1",
          "payment.completed",
          "{}",
          "PENDING",
          null,
          Instant.EPOCH);

  OutboxRelay relay() {
    when(template.execute(any()))
        .thenAnswer(
            call ->
                ((TransactionCallback<?>) call.getArgument(0))
                    .doInTransaction(new SimpleTransactionStatus()));
    when(outbox.claimPending(anyInt(), any())).thenReturn(List.of(message));
    when(listener.handles("payment.completed")).thenReturn(true);
    return new OutboxRelay(
        outbox, events, template, PaymentsProperties.defaults(), List.of(listener));
  }

  @Test
  void theListenerRunsBeforeTheMerchantAndTheRowIsMarkedOnce() {
    relay().relay();

    InOrder sequence = inOrder(listener, events, outbox);
    sequence.verify(listener).on(message);
    sequence.verify(events).emitRaw(any(), any(), any(), any(), any(), any());
    sequence.verify(outbox).markSent("01MSG");
  }

  @Test
  void aListenerThatThrowsKeepsTheRowUnsentAndTheMerchantUnnotified() {
    OutboxRelay relay = relay();
    doThrow(new IllegalStateException("db down")).when(listener).on(message);

    relay.relay();

    verify(events, never()).emitRaw(any(), any(), any(), any(), any(), any());
    verify(outbox, never()).markSent(any());
    verify(outbox).release("01MSG");
  }
}
