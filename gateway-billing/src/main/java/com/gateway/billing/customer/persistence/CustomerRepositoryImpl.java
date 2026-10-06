package com.gateway.billing.customer.persistence;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.security.Sealer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opening the sealed document lives here and nowhere else: the domain holds a Document, the table
 * holds ciphertext, and this class is the only one that sees both.
 */
@Repository
public class CustomerRepositoryImpl implements CustomerRepository {
  private final CustomerJpaRepository jpa;
  private final Sealer sealer;
  private final JsonMapper json = JsonMapper.builder().build();

  @PersistenceContext private EntityManager entityManager;

  public CustomerRepositoryImpl(CustomerJpaRepository jpa, Sealer sealer) {
    this.jpa = jpa;
    this.sealer = sealer;
  }

  /** persist, not save: the id is assigned (a ULID), same reasoning as PaymentRepositoryImpl. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(Customer customer, byte[] documentCiphertext, String documentHash) {
    CustomerEntity entity = new CustomerEntity();
    entity.id = customer.id();
    entity.merchantId = customer.merchantId().value();
    entity.environment = customer.environment().name();
    entity.name = customer.name().value();
    entity.documentCiphertext = documentCiphertext;
    entity.documentHash = documentHash;
    entity.documentKind = customer.document().isCompany() ? "CNPJ" : "CPF";
    entity.email = customer.email();
    entity.address = addressJson(customer.address());
    entity.version = customer.version();
    entity.createdAt = customer.createdAt();
    entity.updatedAt = customer.updatedAt();
    entity.deletedAt = customer.deletedAt();
    entityManager.persist(entity);
  }

  @Override
  @Transactional
  public boolean update(Customer customer) {
    int updated =
        jpa.updateIfVersion(
            customer.id(),
            customer.version() - 1,
            customer.name().value(),
            customer.email(),
            addressJson(customer.address()),
            customer.version(),
            customer.updatedAt(),
            customer.deletedAt());

    return updated == 1;
  }

  @Override
  public Optional<Customer> findActive(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value()).map(this::toDomain);
  }

  @Override
  public Optional<Customer> findActiveByDocumentHash(
      MerchantId merchantId, ProviderEnvironment environment, String documentHash) {
    return jpa.findByMerchantIdAndEnvironmentAndDocumentHashAndDeletedAtIsNull(
            merchantId.value(), environment.name(), documentHash)
        .map(this::toDomain);
  }

  @Override
  public List<Customer> listActive(
      MerchantId merchantId, ProviderEnvironment environment, String cursorId, int limit) {
    return jpa
        .findActivePage(merchantId.value(), environment.name(), cursorId, Limit.of(limit))
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public Map<String, String> activeNames(MerchantId merchantId, Collection<String> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }

    return jpa.findActiveNames(merchantId.value(), ids).stream()
        .collect(
            Collectors.toMap(
                CustomerJpaRepository.CustomerName::getId,
                CustomerJpaRepository.CustomerName::getName));
  }

  private Customer toDomain(CustomerEntity entity) {
    MerchantId merchantId = new MerchantId(entity.merchantId);
    String digits =
        new String(
            sealer.open(entity.documentCiphertext, merchantId.value() + "|customer"),
            StandardCharsets.UTF_8);

    return new Customer(
        entity.id,
        merchantId,
        ProviderEnvironment.valueOf(entity.environment),
        PersonName.of(entity.name),
        Document.of(digits),
        entity.email,
        addressFrom(entity.address),
        entity.version,
        entity.createdAt,
        entity.updatedAt,
        entity.deletedAt);
  }

  private String addressJson(CustomerAddress address) {
    if (address == null) {
      return null;
    }

    Map<String, String> body = new LinkedHashMap<>();
    body.put("street", address.street());
    body.put("district", address.district());
    body.put("city", address.city());
    body.put("state", address.state().value());
    body.put("zip", address.zip().digits());

    return json.writeValueAsString(body);
  }

  @SuppressWarnings("unchecked")
  private CustomerAddress addressFrom(String address) {
    if (address == null) {
      return null;
    }

    Map<String, String> body = json.readValue(address, Map.class);

    return new CustomerAddress(
        body.get("street"),
        body.get("district"),
        body.get("city"),
        Uf.of(body.get("state")),
        ZipCode.of(body.get("zip")));
  }
}
