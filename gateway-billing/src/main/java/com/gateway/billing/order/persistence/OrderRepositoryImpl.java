package com.gateway.billing.order.persistence;

import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderPayer;
import com.gateway.billing.order.OrderStatus;
import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.security.Sealer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Like {@code CustomerRepositoryImpl}, the only class that sees both the inline payer's Document
 * and its ciphertext: the domain holds digits, the JSON column holds {@code document_ciphertext}.
 */
@Repository
public class OrderRepositoryImpl implements OrderRepository {
  private final OrderJpaRepository jpa;
  private final Sealer sealer;
  private final JsonMapper json = JsonMapper.builder().build();

  @PersistenceContext private EntityManager entityManager;

  public OrderRepositoryImpl(OrderJpaRepository jpa, Sealer sealer) {
    this.jpa = jpa;
    this.sealer = sealer;
  }

  /** persist, not save: the id is assigned (a ULID), same reasoning as PaymentRepositoryImpl. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(Order order) {
    OrderEntity entity = new OrderEntity();
    entity.id = order.id();
    entity.merchantId = order.merchantId().value();
    entity.environment = order.environment().name();
    entity.customerId = order.customerId();
    entity.payer = payerJson(order.merchantId(), order.payer());
    entity.amount = order.amount().cents();
    entity.currency = order.amount().currency();
    entity.reference = order.reference();
    entity.description = order.description();
    entity.status = order.status().name();
    entity.paidPaymentId = order.paidPaymentId();
    entity.paidAt = order.paidAt();
    entity.expiresAt = order.expiresAt();
    entity.subscriptionId = order.subscriptionId();
    entity.invoiceNumber = order.invoiceNumber();
    entity.periodStart = order.periodStart();
    entity.periodEnd = order.periodEnd();
    entity.checkoutTokenHash = order.checkoutTokenHash();
    entity.version = order.version();
    entity.createdAt = order.createdAt();
    entity.updatedAt = order.updatedAt();
    entityManager.persist(entity);
  }

  /**
   * Read-then-persist, not persist-and-catch: a unique violation inside the caller's transaction
   * marks it rollback-only, and that transaction also holds the subscription update.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Order> insertInvoiceIfAbsent(Order invoice) {
    Optional<Order> existing =
        jpa.findBySubscriptionIdAndInvoiceNumber(invoice.subscriptionId(), invoice.invoiceNumber())
            .map(this::toDomain);
    if (existing.isPresent()) {
      return existing;
    }

    insert(invoice);

    return Optional.empty();
  }

  @Override
  public Optional<Order> findByCheckoutTokenHash(String hash) {
    return jpa.findByCheckoutTokenHash(hash).map(this::toDomain);
  }

  @Override
  @Transactional
  public boolean update(Order order) {
    int updated =
        jpa.updateIfVersion(
            order.id(),
            order.version() - 1,
            order.status().name(),
            order.paidPaymentId(),
            order.paidAt(),
            order.checkoutTokenHash(),
            order.version(),
            order.updatedAt());

    return updated == 1;
  }

  @Override
  public Optional<Order> find(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantId(id, merchantId.value()).map(this::toDomain);
  }

  @Override
  public Optional<Order> findById(String id) {
    return jpa.findById(id).map(this::toDomain);
  }

  @Override
  public List<Order> findByReference(MerchantId merchantId, String reference, int limit) {
    return jpa
        .findByMerchantIdAndReferenceOrderByCreatedAtDesc(
            merchantId.value(), reference, PageRequest.of(0, limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public List<Order> list(
      MerchantId merchantId,
      ProviderEnvironment environment,
      OrderStatus status,
      String cursorId,
      int limit) {
    return jpa
        .findPage(
            merchantId.value(),
            environment.name(),
            status == null ? null : status.name(),
            cursorId,
            Limit.of(limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public List<Order> listByCustomer(
      MerchantId merchantId, String customerId, OrderStatus status, String cursorId, int limit) {
    return jpa
        .findPageByCustomer(
            merchantId.value(),
            customerId,
            status == null ? null : status.name(),
            cursorId,
            Limit.of(limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public List<Order> findBySubscription(String subscriptionId, int limit) {
    return jpa
        .findBySubscriptionIdOrderByInvoiceNumberDesc(subscriptionId, PageRequest.of(0, limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public List<Order> findOpenExpiredBefore(Instant now, int limit) {
    return jpa
        .findByStatusAndExpiresAtBefore(OrderStatus.OPEN.name(), now, PageRequest.of(0, limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  /**
   * One conditional UPDATE: two claimers serialize on the row lock and only one sees 1. Its own
   * transaction so the marker is visible to other callers before the bank call starts.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimAttempt(String orderId, Instant now, Duration lock) {
    int updated =
        entityManager
            .createNativeQuery(
                "UPDATE billing.orders SET attempt_in_progress_at = ?1 WHERE id = ?2"
                    + " AND status = 'OPEN'"
                    + " AND (attempt_in_progress_at IS NULL OR attempt_in_progress_at < ?3)")
            .setParameter(1, now)
            .setParameter(2, orderId)
            .setParameter(3, now.minus(lock))
            .executeUpdate();

    return updated == 1;
  }

  /**
   * Keyed on the claim's instant: a call slower than the lock lets another caller claim the slot,
   * and releasing by id alone would clear that caller's marker while its charge is still in flight.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseAttempt(String orderId, Instant claimedAt) {
    entityManager
        .createNativeQuery(
            "UPDATE billing.orders SET attempt_in_progress_at = NULL"
                + " WHERE id = ?1 AND attempt_in_progress_at = ?2")
        .setParameter(1, orderId)
        .setParameter(2, claimedAt)
        .executeUpdate();
  }

  /**
   * ON CONFLICT DO NOTHING rather than persist-and-catch: a unique violation inside the consumer's
   * transaction would mark it rollback-only and lose the reaction it was guarding.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean recordProcessedEvent(String eventId, Instant at) {
    int inserted =
        entityManager
            .createNativeQuery(
                "INSERT INTO billing.processed_events (event_id, processed_at) VALUES (?, ?)"
                    + " ON CONFLICT DO NOTHING")
            .setParameter(1, eventId)
            .setParameter(2, at)
            .executeUpdate();

    return inserted == 1;
  }

  private Order toDomain(OrderEntity entity) {
    MerchantId merchantId = new MerchantId(entity.merchantId);

    return Order.rehydrate(
        entity.id,
        merchantId,
        ProviderEnvironment.valueOf(entity.environment),
        entity.customerId,
        payerFrom(merchantId, entity.payer),
        new Money(entity.amount, entity.currency),
        entity.reference,
        entity.description,
        OrderStatus.valueOf(entity.status),
        entity.paidPaymentId,
        entity.paidAt,
        entity.expiresAt,
        entity.subscriptionId,
        entity.invoiceNumber,
        entity.periodStart,
        entity.periodEnd,
        entity.version,
        entity.createdAt,
        entity.updatedAt,
        entity.checkoutTokenHash);
  }

  private String payerJson(MerchantId merchantId, OrderPayer payer) {
    if (payer == null) {
      return null;
    }

    byte[] sealed =
        sealer.seal(
            payer.document().digits().getBytes(StandardCharsets.UTF_8), context(merchantId));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", payer.name().value());
    body.put("document_ciphertext", Base64.getEncoder().encodeToString(sealed));
    body.put("email", payer.email());
    body.put("address", addressMap(payer.address()));

    return json.writeValueAsString(body);
  }

  @SuppressWarnings("unchecked")
  private OrderPayer payerFrom(MerchantId merchantId, String payer) {
    if (payer == null) {
      return null;
    }

    Map<String, Object> body = json.readValue(payer, Map.class);
    byte[] sealed = Base64.getDecoder().decode((String) body.get("document_ciphertext"));
    String digits = new String(sealer.open(sealed, context(merchantId)), StandardCharsets.UTF_8);

    return new OrderPayer(
        PersonName.of((String) body.get("name")),
        Document.of(digits),
        (String) body.get("email"),
        addressFrom((Map<String, String>) body.get("address")));
  }

  private static Map<String, String> addressMap(CustomerAddress address) {
    if (address == null) {
      return null;
    }

    Map<String, String> body = new LinkedHashMap<>();
    body.put("street", address.street());
    body.put("district", address.district());
    body.put("city", address.city());
    body.put("state", address.state().value());
    body.put("zip", address.zip().digits());

    return body;
  }

  private static CustomerAddress addressFrom(Map<String, String> body) {
    if (body == null) {
      return null;
    }

    return new CustomerAddress(
        body.get("street"),
        body.get("district"),
        body.get("city"),
        Uf.of(body.get("state")),
        ZipCode.of(body.get("zip")));
  }

  /** Its own context: a customer's ciphertext must not open as an order payer's, nor back. */
  private static String context(MerchantId merchantId) {
    return merchantId.value() + "|order-payer";
  }
}
