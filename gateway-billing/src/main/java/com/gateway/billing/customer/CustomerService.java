package com.gateway.billing.customer;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.customer.persistence.CustomerRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.security.Sealer;
import com.gateway.kernel.security.Sha256;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

public class CustomerService {
  private final CustomerRepository customers;
  private final SavedCards savedCards;
  private final ActiveSubscriptionsCheck activeSubscriptions;
  private final BillingEvents events;
  private final Sealer sealer;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public CustomerService(
      CustomerRepository customers,
      SavedCards savedCards,
      ActiveSubscriptionsCheck activeSubscriptions,
      BillingEvents events,
      Sealer sealer,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.customers = customers;
    this.savedCards = savedCards;
    this.activeSubscriptions = activeSubscriptions;
    this.events = events;
    this.sealer = sealer;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Customer create(Customer customer) {
    String hash = documentHash(customer.document());
    byte[] sealed =
        sealer.seal(
            customer.document().digits().getBytes(StandardCharsets.UTF_8),
            context(customer.merchantId()));

    try {
      return unitOfWork.inTransaction(
          () -> {
            customers.insert(customer, sealed, hash);

            // Cards saved before this customer existed carry only the hash (plan D); they are his.
            savedCards.adoptByDocumentHash(
                customer.merchantId(), customer.environment(), hash, customer.id());

            events.emit(
                customer.merchantId(),
                "customer.created",
                customer.id(),
                customer.id(),
                json(customer));

            return customer;
          });
    } catch (DataIntegrityViolationException duplicate) {
      Customer existing =
          customers
              .findActiveByDocumentHash(customer.merchantId(), customer.environment(), hash)
              .orElseThrow(() -> duplicate);

      throw new CustomerExistsException(existing.id());
    }
  }

  public Customer get(MerchantId merchantId, String id) {
    return customers
        .findActive(merchantId, id)
        .orElseThrow(() -> new NotFoundException("customer", id));
  }

  public List<Customer> list(
      MerchantId merchantId, ProviderEnvironment environment, String cursor, int limit) {
    return customers.listActive(merchantId, environment, cursor, limit);
  }

  public Optional<Customer> findByDocument(
      MerchantId merchantId, ProviderEnvironment environment, String document) {
    return customers.findActiveByDocumentHash(
        merchantId, environment, documentHash(Document.of(document)));
  }

  public Customer update(
      MerchantId merchantId, String id, String name, String email, CustomerAddress.Raw address) {
    Customer current = get(merchantId, id);

    PersonName newName =
        name == null ? current.name() : CustomerFactory.field("name", () -> PersonName.of(name));
    CustomerAddress newAddress = address == null ? current.address() : CustomerAddress.of(address);
    String newEmail = email == null ? current.email() : email;
    Customer changed = current.withChanges(newName, newEmail, newAddress, clock.instant());

    return unitOfWork.inTransaction(
        () -> {
          if (!customers.update(changed)) {
            throw new DomainException("CONFLICT", "customer " + id + " changed concurrently");
          }

          events.emit(merchantId, "customer.updated", id, id, json(changed));

          return changed;
        });
  }

  public void delete(MerchantId merchantId, String id) {
    Customer current = get(merchantId, id);
    if (activeSubscriptions.hasActive(merchantId, id)) {
      throw new DomainException(
          "CUSTOMER_HAS_ACTIVE_SUBSCRIPTION", "customer " + id + " has an active subscription");
    }

    unitOfWork.run(
        () -> {
          if (!customers.update(current.deleted(clock.instant()))) {
            throw new DomainException("CONFLICT", "customer " + id + " changed concurrently");
          }
        });
  }

  public List<SavedCard> cardsOf(MerchantId merchantId, String id) {
    get(merchantId, id);

    return savedCards.listByCustomer(merchantId, id);
  }

  /**
   * Cards saved under this customer's document and not yet anyone's become his: what {@link
   * #create} does in its transaction, run again after an order attempt for the card it just saved,
   * because the card flow knows only the document hash, never the customer.
   */
  public void adoptSavedCards(Customer customer) {
    String hash = documentHash(customer.document());

    unitOfWork.run(
        () ->
            savedCards.adoptByDocumentHash(
                customer.merchantId(), customer.environment(), hash, customer.id()));
  }

  static String documentHash(Document document) {
    return Sha256.hex(document.digits());
  }

  private static String context(MerchantId merchantId) {
    return merchantId.value() + "|customer";
  }

  public static Map<String, Object> json(Customer customer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", customer.id());
    body.put("name", customer.name().value());
    body.put("document", DocumentMask.mask(customer.document()));
    body.put("email", customer.email());
    body.put("has_address", customer.hasAddress());
    body.put("created_at", customer.createdAt().toString());

    return body;
  }
}
