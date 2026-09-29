package com.gateway.payments.payment.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.payments.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PaymentRepository {
  /**
   * Persists {@code payment} plus the events it produced since it was loaded (or created).
   * Optimistic: the update is {@code WHERE version = expected}, {@code expected} being {@code
   * payment.version()} minus {@code newEvents.size()} — the version the aggregate had before these
   * events were applied in memory. A mismatch (someone else saved first) throws {@link
   * org.springframework.orm.ObjectOptimisticLockingFailureException}.
   */
  Payment save(Payment payment, List<PaymentEvent> newEvents);

  Optional<Payment> findById(String id);

  Optional<Payment> findByMerchantAndId(MerchantId merchantId, String id);

  List<Payment> listByMerchant(MerchantId merchantId, int limit, String cursorId);

  /**
   * By the bank's txid, scoped to the merchant. For Pix the txid is the payment id; for a Bolecode
   * it is the bank's {@code BL…} — the webhook and the reconciliation must resolve both the same
   * way.
   */
  Optional<Payment> findByMerchantAndTxid(MerchantId merchantId, String provider, String txid);

  /**
   * By the acquirer's PaymentId, scoped to the merchant whose notification URL was called: the
   * notification names only the PaymentId, and another merchant's sale must not be reachable
   * through it.
   */
  Optional<Payment> findByMerchantAndCardPaymentId(
      MerchantId merchantId, String provider, String cardPaymentId);

  /** Newest first, capped at {@code limit}. */
  List<Payment> listByMerchantAndReference(MerchantId merchantId, String reference, int limit);

  List<Payment> findPendingOlderThan(Instant expiresBefore, int limit);

  /** Ordered by {@code created_at} ascending, capped at {@code limit}. */
  List<Payment> findByStatusIn(Set<PaymentStatus> statuses, Instant createdAfter, int limit);

  /**
   * Same order and cap as {@link #findByStatusIn}, one method only: the boleto reconciliation pass
   * sharing the mixed query let a backlog of old PENDING Pix rows fill the cap and starve
   * Bolecodes.
   */
  List<Payment> findByMethodAndStatusIn(
      com.gateway.kernel.payment.PaymentMethod method,
      Set<PaymentStatus> statuses,
      Instant createdAfter,
      int limit);

  List<PaymentEvent> events(String paymentId);

  /**
   * {@code SELECT ... FOR UPDATE}; requires an active transaction. For the refund reserve: two
   * concurrent requests must not both see the same "remaining" and together exceed the amount.
   */
  Optional<Payment> findByIdForUpdate(String id);

  /**
   * Like {@link #findByMethodAndStatusIn} but newest first: the card pass reads one sale per row,
   * and when the cap bites the newest are the ones most likely to have a missed notification.
   */
  List<Payment> findNewestByMethodAndStatusIn(
      com.gateway.kernel.payment.PaymentMethod method,
      Set<PaymentStatus> statuses,
      Instant createdAfter,
      int limit);

  /**
   * Oldest first, skipping payments with an OPEN divergence of {@code kind}: an already-flagged row
   * would otherwise be re-read every run and, once there are {@code limit} of them, starve the
   * newer ones forever.
   */
  List<Payment> findByStatusCreatedBeforeWithoutOpenDivergence(
      PaymentStatus status, Instant createdBefore, String kind, int limit);

  /** Ordered by {@code created_at} ascending, capped at {@code limit}. */
  List<Payment> findByStatusCreatedBefore(PaymentStatus status, Instant createdBefore, int limit);
}
